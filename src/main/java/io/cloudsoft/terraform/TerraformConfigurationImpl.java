package io.cloudsoft.terraform;

import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.feed.ssh.SshFeed;
import org.apache.brooklyn.util.text.Strings;

public class TerraformConfigurationImpl extends SoftwareProcessImpl implements TerraformConfiguration {

    private SshFeed sshFeed;

    @Override
    public void init() {
        super.init();
        checkConfiguration();
    }

    @Override
    protected void connectSensors() {
        super.connectSensors();
        connectServiceUpIsRunning();

//        Maybe<SshMachineLocation> machine = Locations.findUniqueSshMachineLocation(getLocations());
//        if (machine.isPresent()) {
//            sshFeed = SshFeed.builder()
//                    .entity(this)
//                    .period(Duration.PRACTICALLY_FOREVER)
//                    .machine(machine.get())
//                    .poll(new SshPollConfig<Boolean>(CONFIGURATION_IS_VALID)
//                            .command(getDriver().makeTerraformCommand("plan"))
//                            .onSuccess(new Function<SshPollValue, Boolean>() {
//                                @Override
//                                public Boolean apply(SshPollValue input) {
//                                    return true;
//                                }})
//                            .onFailure(new Function<SshPollValue, Boolean>() {
//                                @Override
//                                public Boolean apply(SshPollValue input) {
//                                    ServiceStateLogic.setExpectedState(TerraformConfigurationImpl.this, Lifecycle.ON_FIRE);
//                                    return false;
//                                }}))
//                    .build();
//        }
    }

    @Override
    public void disconnectSensors() {
        disconnectServiceUpIsRunning();
        if (sshFeed != null) sshFeed.stop();
        super.disconnectSensors();
    }

    private void checkConfiguration() {
        String configurationUrl = getConfig(CONFIGURATION_URL);
        String configurationContents = getConfig(CONFIGURATION_CONTENTS);

        // Exactly one of the two must have a value
        if (Strings.isBlank(configurationUrl) == Strings.isBlank(configurationContents))
            throw new IllegalArgumentException("Exactly one of the two must have a value: '"
                    + CONFIGURATION_URL.getName() + "' or '" + CONFIGURATION_CONTENTS.getName() + "'.");
    }

    @Override
    public Class<?> getDriverInterface() {
        return TerraformDriver.class;
    }

    @Override
    public TerraformDriver getDriver() {
        return (TerraformDriver) super.getDriver();
    }
}
