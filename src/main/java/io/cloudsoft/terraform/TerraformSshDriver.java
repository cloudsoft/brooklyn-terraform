package io.cloudsoft.terraform;

import static java.lang.String.format;
import static org.apache.brooklyn.util.ssh.BashCommands.commandsToDownloadUrlsAs;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.OsDetails;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.entity.java.JavaSoftwareProcessSshDriver;
import org.apache.brooklyn.entity.software.base.AbstractSoftwareProcessSshDriver;
import org.apache.brooklyn.entity.software.base.lifecycle.ScriptHelper;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.ssh.SshTasks;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.stream.KnownSizeInputStream;
import org.apache.brooklyn.util.text.Strings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

public class TerraformSshDriver extends AbstractSoftwareProcessSshDriver implements TerraformDriver {

    public TerraformSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
        entity.sensors().set(Attributes.LOG_FILE_LOCATION, this.getLogFileLocation());
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    protected String getLogFileLocation() {
        return getStateFilePath();
    }

    @Override
    public void stop() {
        ScriptHelper stopScript = newScript(STOPPING)
                .body.append(makeTerraformCommand("destroy -force -no-color"))
                .environmentVariablesReset(getShellEnvironment())
                .noExtraOutput()
                .gatherOutput();
        int result = stopScript.execute();
        if (result != 0) {
            throw new IllegalStateException("Error executing Terraform destroy: " + stopScript.getResultStderr());
        }
    }

    public String getOsTag() {
        OsDetails os = getLocation().getOsDetails();

        // If no details, assume 64-bit Linux
        if (os == null) return "linux_amd64";

        // If not Mac, assume Linux
        String osType = os.isMac() ? "darwin" : "linux";
        String archType = os.is64bit() ? "amd64" : "386";

        return osType + "_" + archType;
    }

    public String getInstallFilename() {
        return format("terraform_%s_%s.zip", getVersion(), getOsTag());
    }

    @Override
    public void preInstall() {
        resolver = Entities.newDownloader(this, ImmutableMap.of("filename", getInstallFilename()));
        setExpandedInstallDir(Os.mergePaths(getInstallDir(), resolver.getUnpackedDirectoryName(format("terraform_%s_%s", getVersion(), getOsTag()))));
    }

    @Override
    public void install() {
        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();

        List<String> commands = new LinkedList<String>();
        commands.add(BashCommands.INSTALL_ZIP);
        commands.add(BashCommands.INSTALL_UNZIP);
        commands.add(BashCommands.INSTALL_CURL);
        commands.addAll(commandsToDownloadUrlsAs(urls, saveAs));
        commands.add(format("unzip %s", saveAs));

        newScript(INSTALLING).body.append(commands).execute();
    }

    @Override
    public void customize() {
        //Create the directory
        newScript(CUSTOMIZING).execute();
        copyConfiguration();
    }

    @Override
    public void launch() {
        List<String> commands = new LinkedList<String>();
        commands.add(makeTerraformCommand("init -input=false"));
        commands.add(makeTerraformCommand("plan -out=tfplan -input=false"));
        commands.add(makeTerraformCommand("apply -no-color -input=false tfplan"));

        ScriptHelper helper = newScript(LAUNCHING)
                .body.append(commands)
                .failOnNonZeroResultCode(false)
                .noExtraOutput()
                .gatherOutput();
        int result = helper.execute();
        if (result != 0) {
            throw new IllegalStateException("Error executing Terraform plan: " + helper.getResultStderr());
        }
    }

    private void copyConfiguration() {
        InputStream configuration = getConfiguration();
        getMachine().copyTo(configuration, getConfigurationFilePath());
    }

    private InputStream getConfiguration() {
        String configurationUrl = entity.getConfig(TerraformConfiguration.CONFIGURATION_URL);
        if (Strings.isNonBlank(configurationUrl)) {
            return new ResourceUtils(entity).getResourceFromUrl(configurationUrl);
        }
        String configurationContents = entity.getConfig(TerraformConfiguration.CONFIGURATION_CONTENTS);
        if (Strings.isNonBlank(configurationContents)) {
            return KnownSizeInputStream.of(configurationContents);
        }
        throw new IllegalStateException("Could not resolve Terraform configuration from " +
                TerraformConfiguration.CONFIGURATION_URL.getName() + " or " + TerraformConfiguration.CONFIGURATION_CONTENTS.getName());
    }

    private String getConfigurationFilePath() {
        return getRunDir() + "/configuration.tf";
    }

    private String getStateFilePath() {
        return getRunDir() + "/terraform.tfstate";
    }

    @Override
    public String makeTerraformCommand(String argument) {
        return format("cd %s && %s/terraform %s", getRunDir(), getInstallDir(), argument);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> getState() throws IOException {
        String state = DynamicTasks.queue(SshTasks.newSshFetchTaskFactory(getLocation(), getStateFilePath())).asTask().getUnchecked();
        return ImmutableMap.copyOf(new ObjectMapper().readValue(state, LinkedHashMap.class));
    }
}
