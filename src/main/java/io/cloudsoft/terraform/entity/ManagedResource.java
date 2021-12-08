package io.cloudsoft.terraform.entity;

import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.sensor.Sensors;

@ImplementedBy(ManagedResourceImpl.class)
public interface ManagedResource extends TerraformResource {

    AttributeSensor<String> RESOURCE_STATUS = Sensors.newStringSensor("tf.resource.status",
            "The status of this resource");

}
