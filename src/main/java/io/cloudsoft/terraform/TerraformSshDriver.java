package io.cloudsoft.terraform;

import static java.lang.String.format;
import static org.apache.brooklyn.util.ssh.BashCommands.commandsToDownloadUrlsAs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.OsDetails;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.entity.java.JavaSoftwareProcessSshDriver;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.stream.KnownSizeInputStream;
import org.apache.brooklyn.util.text.Strings;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

public class TerraformSshDriver extends JavaSoftwareProcessSshDriver implements TerraformDriver {

    public TerraformSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    protected String getLogFileLocation() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void stop() {
        ((TerraformConfiguration) entity).destroy();
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
        commands.add(BashCommands.INSTALL_CURL);
        commands.addAll(commandsToDownloadUrlsAs(urls, saveAs));
        commands.add(format("unzip %s", saveAs));

        newScript(INSTALLING).body.append(commands).execute();
    }

    @Override
    public void customize() {
      //Create the directory
        newScript(CUSTOMIZING).execute();

        boolean hasConfiguration = copyConfiguration();
        if (!hasConfiguration)
            throw new IllegalStateException("No Terraform configuration could be resolved.");
    }

    @Override
    public void launch() {
        ((TerraformConfiguration) entity).apply();
    }

    private boolean copyConfiguration() {
        Optional<? extends InputStream> configuration = getConfiguration();
        if (!configuration.isPresent())
            return false;

        getMachine().copyTo(configuration.get(), getConfigurationFilePath());
        return true;
    }

    private Optional<? extends InputStream> getConfiguration() {
        String configurationUrl = entity.getConfig(TerraformConfiguration.CONFIGURATION_URL);
        if (!Strings.isBlank(configurationUrl))
            return Optional.of(new ResourceUtils(entity).getResourceFromUrl(configurationUrl));

        String configurationContents = entity.getConfig(TerraformConfiguration.CONFIGURATION_CONTENTS);
        if (!Strings.isBlank(configurationContents))
            return Optional.of(KnownSizeInputStream.of(configurationContents));

        return Optional.absent();
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
    public Map<String, Object> getState() throws JsonParseException, JsonMappingException, IOException {
        return ImmutableMap.copyOf(new ObjectMapper().readValue(new File(getStateFilePath()), LinkedHashMap.class));
    }
}
