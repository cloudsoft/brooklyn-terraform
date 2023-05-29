package io.cloudsoft.terraform.entity;

import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.sensor.Sensors;

import java.util.Map;

@ImplementedBy(ManagedResourceImpl.class)
public interface ManagedResource extends TerraformResource {

    AttributeSensor<String> RESOURCE_STATUS = Sensors.newStringSensor("tf.resource.status", "The status of this resource; " +
            "'changed' if any drift is detetcted, but otherwise set as 'running' by default, but overridden as 'ok' for Startable MRs");

    boolean refreshSensors(Map<String,Object> resource);

    void updateResourceState();
}
