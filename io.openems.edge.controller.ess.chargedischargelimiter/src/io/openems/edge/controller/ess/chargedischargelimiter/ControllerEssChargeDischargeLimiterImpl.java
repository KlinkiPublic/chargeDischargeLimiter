package io.openems.edge.controller.ess.chargedischargelimiter;

import java.time.Duration;
import java.time.Instant;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.ChannelAddress;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Pwr;

import io.openems.edge.common.type.TypeUtils;

import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Ess.ChargeDischargeLimiter", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerEssChargeDischargeLimiterImpl extends AbstractOpenemsComponent
		implements ControllerEssChargeDischargeLimiter, Controller, OpenemsComponent, TimedataProvider {

	private final Logger log = LoggerFactory.getLogger(ControllerEssChargeDischargeLimiterImpl.class);

	private Config config;

	/**
	 * Length of hysteresis in minutes. States are not changed quicker than this.
	 * 
	 */
	private static final int HYSTERESIS = 5; // Seconds
	private Instant lastStateChangeTime = Instant.MIN;
	private Instant balancingStartTime = Instant.MIN;
	private long balancingTime = 0; // Time used for balancing so far
	private int balancingRemainingTime = 0; // Remaining time for balancing. Used in UI
	private Long lastEssActiveChargeEnergy = null;
	private Integer cumulatedchargedEnergy = 0;
	private boolean resetChargedEnergy = false;

	private Integer lastMaxApparentPower = null;

	private int minSoc = 0;

	private int maxSoc = 0;
	private int forceChargeSoc = 0;
	private int forceChargePower = 0;
	private int energyBetweenBalancingCycles = 0;
	private int balancingHysteresisTime = 0;
	private State state = State.UNDEFINED;
	private Integer calculatedPower = null;
	private boolean balancingWanted = false;

	private boolean debugMode = false;

	@Reference
	private ComponentManager componentManager;

	@Reference
	private ConfigurationAdmin cm;

	private ManagedSymmetricEss ess;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	public ControllerEssChargeDischargeLimiterImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerEssChargeDischargeLimiter.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());

		this.config = config;
		try {
			this.ess = this.componentManager.getComponent(config.ess_id());
			this.minSoc = this.config.minSoc(); // min SoC
			this.maxSoc = this.config.maxSoc();
			this.forceChargeSoc = this.config.forceChargeSoc(); // if battery need balancing we charge to this value
			this.forceChargePower = this.config.forceChargePower(); // if battery need balancing we charge to this value
			this.energyBetweenBalancingCycles = this.config.energyBetweenBalancingCycles() * 1000; // convert kWh to Wh
			this.balancingHysteresisTime = this.config.balancingHysteresis();
			this.debugMode = this.config.debugMode();

			if (config.energyBetweenBalancingCycles() > 0) {
				this.balancingWanted = true;
			}

			if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "ess", config.ess_id())) {
				return;
			}
			this.initializeChargedEnergyFromTimedata();

		} catch (OpenemsNamedException e) {

			e.printStackTrace();
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		this.calculatedPower = null; // No constraints
		switch (this.state) {
		case UNDEFINED:
			if (this.ess == null) {
				this.logDebug(this.log, "ERROR. ESS " + this.config.ess_id() + " unavailable ");
				return;
			}
			// Check if we can change to normal operation, i.e. if SOC and activePower
			// Values are available
			if (this.ess.getSoc().get() != null && this.ess.getActivePower().get() != null) {
				this.changeState(State.NORMAL);
			}
			break;
		case NORMAL:
			//
			this._setBalancingRemainingSeconds(0); // no remaining time in normal operation
			// Check if charge energy is below the next balancing cycle. Only balance if
			// This is desired
			if (this.shouldBalance() && this.balancingWanted) {
				this.changeState(State.BALANCING_WANTED);
				break;
			}
			// Check if SOC is in normal limits
			if (this.ess.getSoc().get() > this.maxSoc) {
				this.changeState(State.ABOVE_MAX_SOC);
				break;
			}
			if (this.ess.getSoc().get() <= this.minSoc) {
				this.changeState(State.BELOW_MIN_SOC);
				break;
			}
			break;
		case ERROR:
			// Log errors
			break;
		case BELOW_MIN_SOC:
			// Block discharging
			this.calculatedPower = 0; // Block further discharging

			if (this.ess.getSoc().get() >= this.minSoc) {
				this.changeState(State.NORMAL);
			}
			break;
		case ABOVE_MAX_SOC:

			// Block charging
			this.calculatedPower = 0; // Block further charging
			if (this.ess.getSoc().get() <= this.maxSoc) {
				this.changeState(State.NORMAL);
			}
			break;
		case FORCE_CHARGE_ACTIVE:
			// Force charge with forceChargePower
			// Charge battery with desired power
			// Check if it has reached desired SOC

			if (this.ess.getSoc() != null && this.ess.getSoc().get() > this.forceChargeSoc) { // Desired SOC reached,
			 // stop charging
				this.changeState(State.BALANCING_ACTIVE);
			} else {
				this.calculatedPower = this.forceChargePower * -1; // Charging has a negative value
			}
			break;
		case BALANCING_WANTED:
			// State can be used to check things. Don´t know what, yet ;o)
			this.changeState(State.FORCE_CHARGE_ACTIVE);
			break;
		case BALANCING_ACTIVE:
			// Start Timer
			if (this.balancingStartTime.equals(Instant.MIN)) {
				// Start the balancing timer
				this.balancingStartTime = Instant.now(this.componentManager.getClock());
				this.log.info("Balancing started at " + this.balancingStartTime);
				// ToDo: channel for balancing remaining time needed for UI modal
			}

			// Calculate balancing time so far to seconds
			this.balancingTime = Duration
					.between(this.balancingStartTime, Instant.now(this.componentManager.getClock())).getSeconds();

			// Check if balancing duration has passed
			if (this.balancingTime >= this.balancingHysteresisTime) {
				// Balancing time is over, transition to NORMAL state
				this.logDebug(this.log, "\nBalancing finished. Going back to normal operation  ");
				this.changeState(State.NORMAL);
				this.resetChargedEnergy = true;
				// Reset charged energy
				this.balancingStartTime = Instant.MIN; // Reset the balancing start time
				this.balancingRemainingTime = 0; // Reset remaining time
				break;
			} else {
				this.balancingRemainingTime = (int) (this.balancingHysteresisTime - this.balancingTime);
			}
			this.logDebug(this.log, "Balancing active since " + this.balancingStartTime + "|Remaining: "
					+ this.balancingRemainingTime + "s \n");
			// Stores remaining seconds for balancing in channel
			this._setBalancingRemainingSeconds(this.balancingRemainingTime);

			// Keep battery SOC above desired level. Assume battery is self-discharging
			// constantly
			if (this.ess.getSoc() != null && this.ess.getSoc().get() < (this.forceChargeSoc) + 1) {
				/* SOC is below desired value + 1%, start charging again */
				this.calculatedPower = this.forceChargePower * -1; // Charging has a negative value
			}

			/* SOC is below desired value, reset Timer, start charging again */
			if (this.ess.getSoc() != null && this.ess.getSoc().get() < (this.forceChargeSoc)) {

				this.logDebug(this.log, "\nSoC is below " + this.forceChargeSoc + "%. Stop Balancing");
				this.changeState(State.BALANCING_WANTED);
				break;
			}
			this.calculatedPower = 0; // block further discharging

			break;

		}
		this.calculateChargedEnergy();

		this.applyActivePower(this.calculatedPower);
		// Copied from Emergency Capacity Reserve Controller
		// Set the actual reserve Soc. This channel is used mainly for visualization in
		// UI.
		this._setActualReserveSoc(this.minSoc);

		this._setMaxSoc(this.maxSoc);
		this._setMinSoc(this.minSoc);

		// Save current state
		this._setStateMachine(this.state);
		// Remember: Negative values for Charge; positive for Discharge
		this.logDebug(this.log,
				"\nCurrent State " + this.state.getName() + "\n" + "Current SoC " + this.ess.getSoc().get() + "% \n"
						+ "Current ActivePower " + this.ess.getActivePower().get() + "W \n"
						+ "Energy charged since last balancing " + this.getChargedEnergy().get() + "Wh \n"
						+ " lastMaxApparentPower: " + this.lastMaxApparentPower);
	}

	/*
	 * Positive values (discharging) are ignored.
	 */
	private void calculateChargedEnergy() {
		// Ess Active Charge Energy directly from ESS (cumulative)
		Long currentEssActiveChargeEnergy = this.ess.getActiveChargeEnergy().get(); // Cumulative ESS charge energy
		Integer storedChargedEnergy = this.getChargedEnergy().get(); // Stored charged energy from this controller's
		// channel

		// If it's the first time or if the lastEssActiveChargeEnergy is null,
		// initialize it
		if (this.lastEssActiveChargeEnergy == null) {
			this.lastEssActiveChargeEnergy = currentEssActiveChargeEnergy;
			return;
		}

		// Calculate energy difference
		int energyDifference = (int) (currentEssActiveChargeEnergy - this.lastEssActiveChargeEnergy);

		// Update charged energy by adding the difference
		this.cumulatedchargedEnergy = storedChargedEnergy + energyDifference;

		// Update the last known cumulative energy
		this.lastEssActiveChargeEnergy = currentEssActiveChargeEnergy;

		// If reset is flagged (e.g., calibration completed), reset the charged energy
		if (this.resetChargedEnergy) {
			this.cumulatedchargedEnergy = 0;
			this.resetChargedEnergy = false;
		}
		this.logDebug(this.log, "Writing charged energy");
		// Set the updated charged energy in the controller's channel
		this._setChargedEnergy(this.cumulatedchargedEnergy);

	}

	/**
	 * Calculates if the battery needs too be balanced. This depends on charged
	 * energy since the last balancing procedure. If charged energy exceeds
	 * configured energy method returns true
	 * 
	 * @return if battery should be balanced
	 */
	private boolean shouldBalance() {
		// this.logDebug(this.log, "\nCharged " + this.getActiveChargeEnergy().get() + "
		// since last balancing cycle");
		if (this.state == State.BALANCING_ACTIVE || this.getChargedEnergy().get() == null) {
			return false; // early return
		}
		if (this.getChargedEnergy().get() > this.energyBetweenBalancingCycles) {
			return true;
		}
		return false;
	}

	/**
	 * Applies constraints to ess. Even FORCE_CHARGE is a constraint as the ESS is
	 * allowed to charge more than this controller´s desired value.
	 * 
	 * 
	 * @param calculatedPower .
	 */
	void applyActivePower(Integer calculatedPower) {
		if (calculatedPower == null) {
			// early return if no constrains have to be set
			return;
		}

		try {
			this.logDebug(this.log,">>>ChargeDischargeLimiter Contraints ");			
			// adjust value so that it fits into Min/MaxActivePower
			if (this.state == State.BELOW_MIN_SOC) { // block further discharging
				
				this.ess.setActivePowerLessOrEquals(calculatedPower);
				this.logDebug(this.log, "setActivePowerLessOrEquals -> " + calculatedPower +"W");						
			} else if (this.state == State.ABOVE_MAX_SOC) { // block further charging
				
				this.ess.setActivePowerGreaterOrEquals(calculatedPower);
				this.logDebug(this.log, "setActivePowerGreaterOrEquals -> " + calculatedPower +"W");				
			} else if (this.state == State.FORCE_CHARGE_ACTIVE) { // force charging
				this.calculatedPower = this.ess.getPower().fitValueIntoMinMaxPower(this.id(), this.ess, Phase.ALL,
						Pwr.ACTIVE, calculatedPower);
				this.ess.setActivePowerLessOrEquals(calculatedPower);
				this.logDebug(this.log, "setActivePowerLessOrEquals -> " + calculatedPower +"W");				
			} else if (this.state == State.BALANCING_ACTIVE) { //
				calculatedPower = this.ess.getPower().fitValueIntoMinMaxPower(this.id(), this.ess, Phase.ALL,
						Pwr.ACTIVE, calculatedPower);
				this.ess.setActivePowerLessOrEquals(calculatedPower);
				this.logDebug(this.log, "setActivePowerLessOrEquals -> " + calculatedPower +"W");				

			}
		} catch (OpenemsNamedException e) {
			// ToDo catch exception. Add logging
			e.printStackTrace();
		}
		this.logDebug(this.log,"\n");	
	}

	/**
	 * Changes the state if hysteresis time passed, to avoid rapid changes.
	 *
	 * @param nextState the target state
	 * @return whether the state was changed
	 */
	private boolean changeState(State nextState) {
		if (this.state == nextState) {
			this._setAwaitingHysteresisValue(false);
			return false;
		}
		if (Duration.between(//
				this.lastStateChangeTime, //
				Instant.now(this.componentManager.getClock()) //
		).toSeconds() >= HYSTERESIS) {
			this.state = nextState;
			this.lastStateChangeTime = Instant.now(this.componentManager.getClock());
			this._setAwaitingHysteresisValue(false);
			return true;
		} else {
			this._setAwaitingHysteresisValue(true);
			return false;
		}
	}

	/**
	 * Initialize cumulated energy value from Timedata service.
	 */
	private void initializeChargedEnergyFromTimedata() {
		// ControllerEssChargeDischargeLimiter.ChannelId.STATE_MACHINE;
		var timedata = this.getTimedata();

		if (timedata == null) {
			this.logDebug(this.log, "Timedata service is not available.");
			return; // Exit if timedata service is not available
		}

		this.logDebug(this.log, "Querying Timedata service for the latest energy value...");

		this.timedata
				.getLatestValue(new ChannelAddress(this.id(),
						ControllerEssChargeDischargeLimiter.ChannelId.CHARGED_ENERGY.id()))
				.thenAccept(chargedEnergy -> {
					if (chargedEnergy.isPresent()) {
						Integer value = TypeUtils.getAsType(OpenemsType.INTEGER, chargedEnergy.get());
						this.logDebug(this.log, "Fetched latest ChargedEnergy value: " + value);
						this._setChargedEnergy(value);
					} else {
						this.logDebug(this.log, "No current energy value found for ChargedEnergy channel");
						this._setChargedEnergy(0);
					}
				});
	}

	/**
	 * Uses Info Log for further debug features.
	 */
	@Override
	protected void logDebug(Logger log, String message) {
		if (this.debugMode) {
			this.logInfo(this.log, message);
		}
	}

	@Override
	public Timedata getTimedata() {

		return this.timedata;
	}

}
