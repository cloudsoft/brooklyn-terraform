package io.cloudsoft.terraform.predicates;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.util.guava.SerializablePredicate;
import org.apache.brooklyn.util.text.StringPredicates;

public class SensorMatches implements SerializablePredicate<Entity> {

    public static Predicate<Entity> sensorMatches(final String name, final String regex) {
        return new SensorMatches(name, regex);
    }

    private String name;
    private String regex;

    private SensorMatches() { }

    public SensorMatches(@Nonnull String name, @Nonnull String regex) {
        this.name = Preconditions.checkNotNull(name);
        this.regex = Preconditions.checkNotNull(regex);
    }

    @Override
    public boolean apply(@Nullable Entity entity) {
        if (entity == null) return false;
        String value = entity.getAttribute(Sensors.newStringSensor(name));
        return StringPredicates.matchesRegex(regex).apply(value);
    }

    @Override
    public String toString() {
        return "SensorMatches(" + name + ", " + regex + ")";
    }
}