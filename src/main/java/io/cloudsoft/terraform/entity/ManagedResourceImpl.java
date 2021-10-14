package io.cloudsoft.terraform.entity;

import io.cloudsoft.terraform.TerraformConfiguration;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.stock.BasicEntityImpl;

import java.util.Collection;
import java.util.Map;

public class ManagedResourceImpl extends BasicEntityImpl implements ManagedResource {

    AttributeSensor<Boolean> INSTANCE_UP = Sensors.newBooleanSensor("instance.status",
            "Whether the instance is active and available (confirmed and monitored). Can also show 'tainted'.");

    @Override
    public void init() {
        super.init();
        connectSensors();
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        // terraform started this, nothing to be done here
        connectSensors();
    }

    protected void connectSensors() {
        Map<String, Object> resourceDetails = this.getConfig(ManagedResource.STATE_CONTENTS);
        resourceDetails.forEach((k,v) -> sensors().set(Sensors.newStringSensor(k), v.toString()));
        sensors().set(SERVICE_UP, Boolean.TRUE);
        if ("up".equals(resourceDetails.get("instance.status"))) {
            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.TRUE);
            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        }
    }

    @Override
    public void stop() {
        destroy();
    }

    @Override
    public void destroy() {
        ((TerraformConfiguration)getParent()).destroyTarget(this);
    }

    @Override
    public void restart() {
        // figure out how to do this - get the location from Resource !? Use terrraform ?
        //Do we even want to allow control from AMP?
    }
}
