package io.openems.edge.controller.symmetric.thresholdpeakshaver;
import io.openems.common.types.OptionsEnum;

public enum State implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	STANDBY(0, "Peakshaver is inactive and waiting"), // SoC in range between min and max
	ERROR(1, "Error State"), //
	PEAKSHAVING_ACTIVE(2, "Active Peak Shaving"), //
	CHARGING_ACTIVE(3, "No Active Peak Shaving Since Hysteresis Start"),
	HYSTERESIS_ACTIVE(4, "Waiting. No Active Peak Shaving Since Hysteresis Start"),
	GRID_POWER_ABOVE_LIMIT(5, "No Active Peak Shaving Since Hysteresis Start");


	private final int value;
	private final String name;

	private State(int value, String name) {
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