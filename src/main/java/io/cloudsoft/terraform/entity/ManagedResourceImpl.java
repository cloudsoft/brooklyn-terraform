package io.cloudsoft.terraform.entity;

import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.stock.BasicEntityImpl;

import java.util.Map;

public class ManagedResourceImpl extends BasicEntityImpl implements  ManagedResource {

    @Override
    public void init() {
        super.init();
        connectSensors();
    }

    protected void connectSensors() {
        Map<String, Object> resourceDetails = this.getConfig(StartableManagedResource.STATE_CONTENTS);
        resourceDetails.forEach((k,v) -> sensors().set(Sensors.newStringSensor("tf." + k), v.toString()));
        if(!resourceDetails.containsKey("resource.status")) {
            sensors().set(RESOURCE_STATUS, "ok"); // the provider doesn't provide any property to let us know the state of the resource
        }
        this.setDisplayName(getConfig(StartableManagedResource.ADDRESS));
        updateResourceState();
    }

    @Override
    public boolean refreshSensors(Map<String, Object> resource) {
        resource.forEach((k, v) -> sensors().set(Sensors.newStringSensor("tf." + k), v.toString()));
        if (!(sensors().get(RESOURCE_STATUS).equals("ok") || sensors().get(RESOURCE_STATUS).equals("running"))) sensors().set(RESOURCE_STATUS, "ok");
        return true;
    }

    @Override
    public void updateResourceState() {
        sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.CREATED);
    }
}
