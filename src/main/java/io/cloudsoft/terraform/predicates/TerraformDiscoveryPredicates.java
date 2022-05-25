package io.cloudsoft.terraform.predicates;

import com.google.common.base.Predicate;
import org.apache.brooklyn.api.entity.Entity;

public class TerraformDiscoveryPredicates {
    /** Included for rebind support. */
    public static Predicate<Entity> sensorMatches(final String sensorName, final String sensorValueRegex) {
        return SensorMatches.sensorMatches(sensorName, sensorValueRegex);
    }
}
