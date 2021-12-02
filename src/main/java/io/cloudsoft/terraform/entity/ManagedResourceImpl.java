package io.cloudsoft.terraform.entity;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import io.cloudsoft.terraform.TerraformConfiguration;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.stock.BasicEntityImpl;

import java.util.Collection;
import java.util.Map;

public class ManagedResourceImpl extends BasicEntityImpl implements ManagedResource {

    public static final ImmutableList<String> ACCEPTED_STATE = ImmutableList.of("ok", "running", "up", "online");

    @Override
    public void init() {
        super.init();
        connectSensors();
        config().set(ConfigKeys.builder(String.class)
                .name("destination")
                .description("Where to POST the messages.")
                .constraint(Predicates.notNull())
                .defaultValue("http://ness_ip:8181")
                .reconfigurable(true)
                .build() , "");
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        // terraform started this, nothing to be done here
        connectSensors();
    }

    protected void connectSensors() {
        Map<String, Object> resourceDetails = this.getConfig(ManagedResource.STATE_CONTENTS);
        resourceDetails.forEach((k,v) -> sensors().set(Sensors.newStringSensor("tf." + k), v.toString()));
        if(!resourceDetails.containsKey("resource.status")) {
            sensors().set(RESOURCE_STATUS, "ok"); // the provider doesn't provide any property to let us know the state of the resource
        }
        sensors().set(SERVICE_UP, Boolean.TRUE);
        this.setDisplayName(getConfig(ManagedResource.ADDRESS));
        updateResourceState();
    }

    @Override
    public boolean refreshSensors(Map<String, Object> resource) {
        resource.forEach((k, v) -> sensors().set(Sensors.newStringSensor("tf." + k), v.toString()));
        updateResourceState();
        return true;
    }

    @Override
    public void stop() {
        // stop means different things for different resource types.
        // TODO consider executing an operation based on the resource type
    }


    @Override
    public void restart() {
        // figure out how to do this - get the location from Resource !? Use terrraform ?
        // check comments on stop as well
        // TODO Do we even want to allow control from AMP?
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
