package io.openems.edge.controller.symmetric.thresholdpeakshaver;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller Threshold Peak-Shaving Symmetric", //
		description = "Cuts power peaks and recharges the battery in low consumption periods. Works above defined threshold")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlPeakShaving0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Ess-ID", description = "ID of Ess device.")
	String ess_id();

	@AttributeDefinition(name = "Grid-Meter-ID", description = "ID of the Grid-Meter.")
	String meter_id();

	@AttributeDefinition(name = "Peak-Shaving threshold", description = "Peak shaving / battery charging begins above that value. Below threshold the controller does nothing. Battery can be used i.e. for balancing purposes")
	int peakShavingThresholdPower();

	@AttributeDefinition(name = "Hyteresis", description = "Timer start with first 'peak shaved'. State 'Peakshaving active' stays on till hystereses time is over")
	int hysteresisTime() default 3600;
	
	@AttributeDefinition(name = "Peak-Shaving power", description = "Grid purchase power above this value is considered a peak and shaved to this value.")
	int peakShavingPower();

	@AttributeDefinition(name = "Recharge power", description = "If grid purchase power is below this value battery is recharged.")
	int rechargePower();
	
	@AttributeDefinition(name = "Debug Mode", description = "Extends debugging")
	boolean debugMode() default true;		

	@AttributeDefinition(name = "Ess target filter", description = "This is auto-generated by 'Ess-ID'.")
	String ess_target() default "(enabled=true)";

	String webconsole_configurationFactory_nameHint() default "Controller Threshold Peak-Shaving Symmetric [{id}]";
}