package io.cloudsoft.terraform.entity;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.stock.BasicEntityImpl;
import org.apache.brooklyn.util.net.UserAndHostAndPort;

import java.util.Collection;
import java.util.Map;

public class StartableManagedResourceImpl extends ManagedResourceImpl implements StartableManagedResource {

    public static final ImmutableList<String> ACCEPTED_STATE = ImmutableList.of("ok", "running", "up", "online");


    protected void connectSensors() {
        Map<String, Object> resourceDetails = this.getConfig(StartableManagedResource.STATE_CONTENTS);
        resourceDetails.forEach((k,v) -> sensors().set(Sensors.newStringSensor("tf." + k), v.toString()));
        if(!resourceDetails.containsKey("resource.status")) {
            sensors().set(RESOURCE_STATUS, "ok"); // the provider doesn't provide any property to let us know the state of the resource
        }
        sensors().set(SERVICE_UP, Boolean.TRUE);
        this.setDisplayName(getConfig(StartableManagedResource.ADDRESS));
        updateResourceState();
    }

    @Override
    public boolean refreshSensors(Map<String, Object> resource) {
        resource.forEach((k, v) -> sensors().set(Sensors.newStringSensor("tf." + k), v.toString()));
        if (resource.containsKey(IP_SENSOR_NAME)) {
            String ip = resource.get(IP_SENSOR_NAME).toString();
            sensors().set(Attributes.SSH_ADDRESS, UserAndHostAndPort.fromParts("", ip, 22));
        }
        updateResourceState();
        return true;
    }

    @Effector(description = "[TBD]Stop the resource based on the type.")
    @Override
    public void stop() {
        // create a task to ssh onto the machine using sensor Attributes.SSH_ADDRESS value and call shutdown
    }

    @Effector(description = "[TBD]Restart the resource based on the type.")
    @Override
    public void restart() {
        // create a task to ssh onto the machine using sensor Attributes.SSH_ADDRESS value and call reboot
    }

    @Effector(description = "[TBD]Start the resource based on the type.")
    @Override
    public void start(Collection<? extends Location> locations) {
        // TODO Do we even want to allow control from AMP?
        // start means different things for different resource types.
        // TODO consider executing an operation based on the resource type
        connectSensors();
    }

    public void updateResourceState(){
        final String resourceStatus = sensors().get(RESOURCE_STATUS);
        if (ACCEPTED_STATE.contains(resourceStatus)) {
            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.TRUE);
            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        } else if (resourceStatus.equalsIgnoreCase(Lifecycle.STOPPING.name())) {
            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.FALSE);
            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPING);
        } else if (resourceStatus.equalsIgnoreCase(Lifecycle.STOPPED.name())) {
            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.FALSE);
            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
        } else  {
            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.TRUE);
            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.ON_FIRE);
        }
    }
}
