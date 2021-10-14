package io.cloudsoft.terraform.entity;

import com.google.common.collect.ImmutableList;
import io.cloudsoft.terraform.TerraformConfiguration;
import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.api.location.Location;
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
        updateServiceState();
    }

    @Override
    public void refreshSensors(Map<String, Object> resource) {
        StateParser.parseResource(resource).forEach((k, v) -> sensors().set(Sensors.newStringSensor(k), v.toString()));
        updateServiceState();
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

    private void updateServiceState(){
        final String instanceStatus = sensors().get(Sensors.newStringSensor("instance.status"));
        if (ACCEPTED_STATE.contains(instanceStatus)) {
            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.TRUE);
            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        }
        if (instanceStatus.equalsIgnoreCase(Lifecycle.STOPPING.name())) {
            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.FALSE);
            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPING);
        }
        if (instanceStatus.equalsIgnoreCase(Lifecycle.STOPPED.name())) {
            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.FALSE);
            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
        }
        // TODO - what do we do if 'shutting-down' or 'terminated'?
    }
}
