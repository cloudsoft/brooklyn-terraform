package io.cloudsoft.terraform;

import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.entity.java.JavaSoftwareProcessSshDriver;
import org.apache.brooklyn.location.ssh.SshMachineLocation;

public class TerraformSshDriver extends JavaSoftwareProcessSshDriver implements TerraformDriver {

    public TerraformSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    public boolean isRunning() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    protected String getLogFileLocation() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void stop() {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void install() {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void customize() {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void launch() {
        // TODO Auto-generated method stub
        
    }
}
