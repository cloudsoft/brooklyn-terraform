package io.cloudsoft.terraform.predicates;

import com.google.common.base.Predicate;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.util.text.StringPredicates;

import javax.annotation.Nullable;

public class TerraformDiscoveryPredicates {

    public static Predicate<Entity> sensorMatches(final String sensorName, final String sensorValueRegex) {
        return new EntityPredicate(sensorValueRegex, sensorName);
    }

    private static class EntityPredicate implements Predicate<Entity> {
        private final String sensorValueRegex;
        private final String sensorName;

        public EntityPredicate(String sensorValueRegex, String sensorName) {
            this.sensorValueRegex = sensorValueRegex;
            this.sensorName = sensorName;
        }

        @Override
        public boolean apply(@Nullable Entity entity) {
            return StringPredicates.matchesRegex(sensorValueRegex).apply(entity.getAttribute(Sensors.newStringSensor(sensorName)));
        }
    }
}
