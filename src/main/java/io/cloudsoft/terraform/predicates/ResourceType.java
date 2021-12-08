package io.cloudsoft.terraform.predicates;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.util.guava.SerializablePredicate;

public class ResourceType implements SerializablePredicate<Entity> {

    public static final String TF_RESOURCE_TYPE = "tf.resource.type";

    public static Predicate<Entity> resourceType(final String type) {
        return new ResourceType(type);
    }

    private String type;

    private ResourceType() { }

    public ResourceType(@Nonnull String type) {
        this.type = Preconditions.checkNotNull(type);
    }

    @Override
    public boolean apply(@Nullable Entity entity) {
        if (entity == null) return false;
        String value = entity.getAttribute(Sensors.newStringSensor(TF_RESOURCE_TYPE));
        return Objects.equals(type, value);
    }

    @Override
    public String toString() {
        return "ResourceType(" + type + ")";
    }
}