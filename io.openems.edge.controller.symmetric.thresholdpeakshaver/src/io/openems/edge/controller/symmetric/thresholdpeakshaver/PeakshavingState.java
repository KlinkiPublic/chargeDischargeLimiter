package io.openems.edge.controller.symmetric.thresholdpeakshaver;
import io.openems.common.types.OptionsEnum;

public enum PeakshavingState implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	IDLE(0, "Peakshaver is inactive and waiting"), // SoC in range between min and max
	ERROR(1, "Error State"), //
	DISABLED(2, "Peak Shaver not active"), //	
	ACTIVE(3, "Active Peak Shaving"), //
	CHARGING(4, "ESS charges"),
	HYSTERESIS_ACTIVE(5, "Waiting. No Active Peak Shaving Since Hysteresis Start");


	private final int value;
	private final String name;

	private PeakshavingState(int value, String name) {
		this.value = value;
		this.name = name;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}
}