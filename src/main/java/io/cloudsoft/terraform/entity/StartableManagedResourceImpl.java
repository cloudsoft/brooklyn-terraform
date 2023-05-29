package io.cloudsoft.terraform.entity;

import com.google.common.collect.ImmutableList;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.util.net.UserAndHostAndPort;

import java.util.Collection;
import java.util.Map;

public class StartableManagedResourceImpl extends ManagedResourceImpl implements StartableManagedResource {

    protected void connectSensors() {
        super.connectSensors();
    }

    @Override
    public boolean refreshSensors(Map<String, Object> resource) {
        if (resource.containsKey(IP_SENSOR_NAME)) {
            String ip = resource.get(IP_SENSOR_NAME).toString();
            sensors().set(Attributes.SSH_ADDRESS, UserAndHostAndPort.fromParts("tbd", ip, 22));
        }
        return super.refreshSensors(resource);
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

    private static final ImmutableList<String> HEALTHY_RESOURCE_STATES = ImmutableList.of("ok", "running",
            // not used currently
            "up", "online"
        );

    public void updateResourceState(){
        final String resourceStatus = sensors().get(RESOURCE_STATUS);

        if (HEALTHY_RESOURCE_STATES.contains(resourceStatus)) {
            sensors().set(SERVICE_UP, Boolean.TRUE);
            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.TRUE);

            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);

            ServiceStateLogic.updateMapSensorEntry(this, Attributes.SERVICE_PROBLEMS,
                    "TF-ASYNC", Entities.REMOVE);

//          // could try to intercept stopping / etc
//        } else if (resourceStatus.equalsIgnoreCase(Lifecycle.STOPPING.name())) {
//            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.FALSE);
//            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPING);
//
//        } else if (resourceStatus.equalsIgnoreCase(Lifecycle.STOPPED.name())) {
//            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.FALSE);
//            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);

        } else  {
            sensors().set(SERVICE_UP, Boolean.FALSE);
            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.FALSE);

            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.ON_FIRE);

            if ("changed".equals(resourceStatus)) {
                ServiceStateLogic.updateMapSensorEntry(this, Attributes.SERVICE_PROBLEMS,
                        "TF-ASYNC", "Resource changed outside terraform.");
            } else {
                ServiceStateLogic.updateMapSensorEntry(this, Attributes.SERVICE_PROBLEMS,
                        "TF-ASYNC", "Resource has unexpected status: "+resourceStatus);
            }
        }
    }
}
