package io.openems.edge.controller.symmetric.thresholdpeakshaver;

import java.time.Duration;
import java.time.Instant;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.common.sum.GridMode;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.ThresholdPeakshaving", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerEssThresholdPeakshaverImpl extends AbstractOpenemsComponent
		implements ControllerEssThresholdPeakshaver, Controller, OpenemsComponent {

	public static final double DEFAULT_MAX_ADJUSTMENT_RATE = 0.2;

	private final Logger log = LoggerFactory.getLogger(ControllerEssThresholdPeakshaverImpl.class);

	@Reference
	private ComponentManager componentManager;

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private ManagedSymmetricEss ess;

	@Reference
	private ElectricityMeter meter;

	private Config config;

	private State state = State.UNDEFINED;
	private PeakshavingState peakshavingState = PeakshavingState.UNDEFINED;
	private static final int HYSTERESIS = 5; // seconds
	private Instant lastStateChangeTime = Instant.MIN;

	private Instant peakshavingStartTime = Instant.MIN;

	public ControllerEssThresholdPeakshaverImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerEssThresholdPeakshaver.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;

		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "ess", config.ess_id())) {
			return;
		}

		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "meter", config.meter_id())) {
			return;
		}

		// Log warnings or handle errors if components are not available
		if (this.ess == null) {
			this.logError(this.log, "ESS component is not available.");
		}
		if (this.meter == null) {
			this.logError(this.log, "Meter component is not available.");
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {

		// Calculate 'real' grid-power (without current ESS charge/discharge)
		var gridPower = meter.getActivePower().getOrError() /* current buy-from/sell-to grid */
				+ ess.getActivePower().getOrError() /* current charge/discharge Ess */;

		// Save grid power without peakshaving
		this._setGridPowerWithoutPeakShaving(gridPower);

		int calculatedPower;



		switch (this.state) {
		case UNDEFINED:
			if (this.checkEnvironment() == false) {
				this.changeState(State.ERROR);
				break;
			}

			this.changeState(State.STANDBY);

			break;
		case ERROR:
			if (this.checkEnvironment() == true) {
				this.changeState(State.STANDBY);
				break;
			}
			break;
		case STANDBY:
			if (this.checkEnvironment() == false) {
				this.changeState(State.ERROR);
				this.changePeakshavingState(PeakshavingState.ERROR);
				break;
			}
			// Stub: check something
			if (gridPower >= this.config.peakShavingThresholdPower()) { // Activate Peakshaving
				// Peakshaving starts above threshold
				// Remember: In peak shaving mode the battery can be charged
				this.changeState(State.PEAKSHAVING_ACTIVE);
			}
			break;
		case PEAKSHAVING_ACTIVE:
			if (this.checkEnvironment() == false) {
				this.changeState(State.ERROR);
				break;
			}
			// If grid power is above threshold update timer
			if (gridPower > this.config.peakShavingThresholdPower()) {
				this.peakshavingStartTime = Instant.now(this.componentManager.getClock()); // Start timer
			}

			if (gridPower >= this.config.peakShavingPower()) {
				/*
				 * Peak-Shaving
				 */
				calculatedPower = gridPower -= this.config.peakShavingPower();
				// if peakshaving is active, save "shaved" power
				if (calculatedPower > 0) {
					this._setPeakShavedGridPower(calculatedPower);
				} 
					
				this.logDebug(this.log, "Peakshaver: Battery Discharging");

				this.changePeakshavingState(PeakshavingState.ACTIVE);

			} else if (gridPower <= this.config.rechargePower()) {
				/*
				 * Recharge
				 */
				calculatedPower = gridPower -= this.config.rechargePower();
				this.logDebug(this.log, "Peakshaver: Battery Charging");
				this.changePeakshavingState(PeakshavingState.CHARGING);
				this._setPeakShavedGridPower(0);

			} else {
				/*
				 * Do nothing
				 */
				calculatedPower = 0;
				this.changePeakshavingState(PeakshavingState.IDLE);
				this._setPeakShavedGridPower(0);

			}

			this.applyPower(ess, calculatedPower);

			// Only leave if grid power is below threshold an hysteresis has passed
			if (this.peakShavingHysteresisActive() == false) {
				// Peakshaving starts above threshold
				// Remember: In peak shaving mode the battery can be charged
				this.changeState(State.STANDBY);
			}
			break;
		default:
			// ToDo
			break;

		}
		// save current state
		
		this.logDebug(this.log,
				"\n PeakShaver Current State " + this.state.getName() +
				"\n PeakShaving State " + this.peakshavingState.getName() +
				"\n Current SoC " + this.ess.getSoc().get() + "%" +
				"\n Current ESS ActivePower " + this.ess.getActivePower().get() + "W" +
				"\n Grid power (without ESS) " + this.getGridPowerWithoutPeakShaving() + "W" +
				"\n Balancing power " + this.getCalculatedPower() + "W" +
				"\n Shaved power " + this.getPeakShavedGridPower() + "W" 
		
		);		

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

	/*
	 * checks - Grid Mode - meter not null - ess not null
	 */
	private boolean checkEnvironment() {
		if (ess.getGridMode() != GridMode.ON_GRID) {
			return false;
		}
		if (this.meter == null || this.ess == null) {
			return false;
		}
		return true;
	}

	private boolean peakShavingHysteresisActive() {
		long peakShavingDuration = Duration
				.between(this.peakshavingStartTime, Instant.now(this.componentManager.getClock())).getSeconds();
		if (peakShavingDuration > this.config.hysteresisTime()) {
			return false;
		} else {
			this.logDebug(this.log, "Hysteresis is active");
			return true;
		}

	}

	/**
	 * Applies the power on the ESS.
	 *
	 * @param ess         {@link ManagedSymmetricEss} where the power needs to be
	 *                    set
	 * @param activePower the active power
	 * @throws OpenemsNamedException on error
	 */
	private void applyPower(ManagedSymmetricEss ess, Integer activePower) throws OpenemsNamedException {
		if (activePower != null) {
			ess.setActivePowerEqualsWithPid(activePower);
			ess.setReactivePowerEquals(0);
			this._setCalculatedPower(activePower); // save value to channel

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

			return false;
		}
		if (Duration.between(//
				this.lastStateChangeTime, //
				Instant.now(this.componentManager.getClock()) //
		).toSeconds() >= HYSTERESIS) {
			this.state = nextState;
			this.lastStateChangeTime = Instant.now(this.componentManager.getClock());
			this._setStateMachine(this.state); // save to channel
			return true;
		} else {

			return false;
		}

	}

	/**
	 * Changes the state if hysteresis time passed, to avoid too quick changes.
	 *
	 * @param nextState the target state
	 * @return whether the state was changed
	 */
	private boolean changePeakshavingState(PeakshavingState nextState) {
		if (this.peakshavingState == nextState) {

			return false;
		}

		// Placeholder for hysteresis - necessary?
		this.peakshavingState = nextState;
		this._setPeakShavingStateMachine(nextState); // save to channel

		return true;
	}
}
