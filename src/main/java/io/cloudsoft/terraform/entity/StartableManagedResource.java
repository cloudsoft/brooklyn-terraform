package io.cloudsoft.terraform.entity;

import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.core.entity.trait.Startable;

@ImplementedBy(StartableManagedResourceImpl.class)
public interface StartableManagedResource extends Startable, ManagedResource {

    String IP_SENSOR_NAME="value.default_ip_address";

}
