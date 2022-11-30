package io.cloudsoft.terraform.predicates;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.util.core.flags.TypeCoercions;
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
        Object value = entity.getAttribute(Sensors.newSensor(Object.class, name));
        String vs = TypeCoercions.coerce(value, String.class);
        return StringPredicates.matchesRegex(regex).apply(vs);
    }

    @Override
    public String toString() {
        return "SensorMatches(" + name + ", " + regex + ")";
    }
}