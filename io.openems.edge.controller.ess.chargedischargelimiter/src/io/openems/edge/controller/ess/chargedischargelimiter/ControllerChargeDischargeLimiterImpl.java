package io.openems.edge.controller.ess.chargedischargelimiter;

import java.time.Duration;
import java.time.Instant;

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
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Ess.ChargeDischargeLimiter", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerChargeDischargeLimiterImpl extends AbstractOpenemsComponent
		implements ControllerChargeDischargeLimiter, TimedataProvider, Controller, OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(ControllerChargeDischargeLimiterImpl.class);

	private final CalculateEnergyFromPower calculateChargeEnergy = new CalculateEnergyFromPower(this,
			ControllerChargeDischargeLimiter.ChannelId.ACTIVE_CHARGE_ENERGY);

	private Config config;

	/**
	 * Length of hysteresis in minutes. States are not changed quicker than this.
	 * 
	 */
	private static final int HYSTERESIS = 10; // seconds
	private Instant lastStateChangeTime = Instant.MIN;
	private Instant balancingStartTime = Instant.MIN;

	private int minSoc = 0;

	private int maxSoc = 0;
	private int forceChargeSoc = 0;
	private int forceChargePower = 0;
	private int energyBetweenBalancingCycles = 0;
	private int balancingHysteresisTime = 0;
	private State state = State.UNDEFINED;
	private Integer calculatedPower = null;

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	private ManagedSymmetricEss ess;

	public ControllerChargeDischargeLimiterImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerChargeDischargeLimiter.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());

		this.config = config;
		try {
			this.ess = this.componentManager.getComponent(config.ess_id());
			this.minSoc = this.config.minSoc(); // min SoC
			this.maxSoc = this.config.minSoc();
			this.forceChargeSoc = this.config.forceChargeSoc(); // if battery need balancing we charge to this value
			this.forceChargePower = this.config.forceChargePower(); // if battery need balancing we charge to this value
			this.energyBetweenBalancingCycles = this.config.energyBetweenBalancingCycles();
			this.balancingHysteresisTime = this.config.balancingHysteresis();
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
// method stub		
		// Remember: Negative values for Charge; positive for Discharge
		this.logDebug(this.log, "\nCurrent State " + this.state.getName());
		this.calculatedPower = null; // No constraints
		switch (this.state) {
		case UNDEFINED:
			// check if we can change to normal operation, i.e. if SOC and activePower
			// values are available
			if (ess.getSoc().get() != null && ess.getActivePower().get() != null) {
				this.changeState(State.NORMAL);
			}
			break;
		case NORMAL:

			// check if charge energy is below the next balancing cycle
			if (shouldBalance()) {
				this.changeState(State.BALANCING_WANTED);
				break;
			}
			// check if SOC is in normal limits
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
			// log errors
			break;
		case BELOW_MIN_SOC:
			// block discharging
			this.calculatedPower = 0; // block further discharging

			if (this.ess.getSoc().get() >= this.minSoc) {
				this.changeState(State.NORMAL);
			}
			break;
		case ABOVE_MAX_SOC:

			// block charging
			this.calculatedPower = 0; // block further charging
			if (this.ess.getSoc().get() <= this.maxSoc) {
				this.changeState(State.NORMAL);
			}
			break;
		case FORCE_CHARGE_ACTIVE:
			// force charge with forceChargePower
			// Charge battery with desired power
			// Check wether it has reached desired SOC

			if (ess.getSoc() != null && ess.getSoc().get() > this.forceChargeSoc) { // desired SOC reached, stop
																					// charging
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
			if (balancingStartTime.equals(Instant.MIN)) {
				// Start the balancing timer
				balancingStartTime = Instant.now(this.componentManager.getClock());
				this.log.info("Balancing started at " + balancingStartTime);
			}
			this.logDebug(this.log, "\nBalancing active since " + this.balancingStartTime);
			// Check if balancing duration has passed
			if (Duration.between(balancingStartTime, Instant.now(this.componentManager.getClock()))
					.getSeconds() >= this.balancingHysteresisTime) {
				// Balancing time is over, transition to NORMAL state
				this.logDebug(this.log, "\nBalancing finished. Going back to normal operation  ");
				this.changeState(State.NORMAL);
				this._setActiveChargeEnergy(0); // Reset charged energy
				balancingStartTime = Instant.MIN; // Reset the balancing start time
				break;
			}

			// Keep battery SOC above desired level. Assume battery is self-discharging
			// constantly
			if (ess.getSoc() != null && ess.getSoc().get() < (this.forceChargeSoc) + 1) {
				/* SOC is below desired value + 1%, start charging again */
				this.calculatedPower = this.forceChargePower * -1; // Charging has a negative value
			}

			/* SOC is below desired value, reset Timer, start charging again */
			if (ess.getSoc() != null && ess.getSoc().get() < (this.forceChargeSoc)) {

				this.logDebug(this.log, "\nSoC is below " + this.forceChargeSoc + "%. Stop Balancing");
				this.changeState(State.BALANCING_WANTED);
				break;
			}
			this.calculatedPower = 0; // block further discharging

			break;

		}
		this.applyActivePower(calculatedPower);
		this.calculateEnergy();

	}

	/**
	 * Calculates if the battery needs too be balanced. This depends on charged
	 * energy since the last balancing procedure. If charged energy exceeds
	 * configured energy method returns true
	 * 
	 * @return if battery should be balanced
	 */
	private boolean shouldBalance() {
		this.logDebug(this.log, "\nCharged " + this.getActiveChargeEnergy().get() + " since last balancing cycle");
		if (this.state == State.BALANCING_ACTIVE) {
			return false; // early return
		}
		if (this.getActiveChargeEnergy().get() > this.energyBetweenBalancingCycles) {
			return true;
		}
		return false;
	}

	/**
	 * Applies constraints to ess. Even FORCE_CHARGE is a constraint as the ESS is
	 * allowed to charge more than this controller´s desired value.
	 * 
	 * 
	 * @param calculatedPower
	 */
	void applyActivePower(Integer calculatedPower) {
		if (calculatedPower == null) {
			// early return if no constrains have to be set
			return;
		}

		try {
			// adjust value so that it fits into Min/MaxActivePower
			if (this.state == State.BELOW_MIN_SOC) { // block further discharging
				ess.setActivePowerLessOrEquals(calculatedPower);
			} else if (this.state == State.ABOVE_MAX_SOC) { // block further charging
				ess.setActivePowerGreaterOrEquals(calculatedPower);
			} else if (this.state == State.FORCE_CHARGE_ACTIVE) { // force charging
				calculatedPower = ess.getPower().fitValueIntoMinMaxPower(this.id(), ess, Phase.ALL, Pwr.ACTIVE,
						calculatedPower);
				ess.setActivePowerLessOrEquals(calculatedPower);
			} else if (this.state == State.BALANCING_ACTIVE) { //
				calculatedPower = ess.getPower().fitValueIntoMinMaxPower(this.id(), ess, Phase.ALL, Pwr.ACTIVE,
						calculatedPower);
				ess.setActivePowerLessOrEquals(calculatedPower);
			}
		} catch (OpenemsNamedException e) {
			// ToDo catch exception. Add logging
			e.printStackTrace();
		}
	}

	/**
	 * Changes the state if hysteresis time passed, to avoid too quick changes.
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
		).toMinutes() >= HYSTERESIS) {
			this.state = nextState;
			this.lastStateChangeTime = Instant.now(this.componentManager.getClock());
			this._setAwaitingHysteresisValue(false);
			return true;
		} else {
			this._setAwaitingHysteresisValue(true);
			return false;
		}
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	/**
	 * Calculates charge energy from power. Positive values (discharging) are
	 * ignored.
	 *
	 * @param nextState the target state
	 * @return whether the state was changed
	 */
	private void calculateEnergy() {
		// Calculate Energy
		Integer activePower = this.ess.getActivePower().get();

		if (activePower == null) {
			// Not available
			this.calculateChargeEnergy.update(null);
			// ToDo: Log Error
		} else if (activePower < 0) {
			this.calculateChargeEnergy.update(activePower * -1);
		} else {
			this.calculateChargeEnergy.update(0);
		}

	}

	/**
	 * Uses Info Log for further debug features.
	 */
	@Override
	protected void logDebug(Logger log, String message) {
		if (this.config.debugMode()) {
			this.logInfo(this.log, message);
		}
	}

}
