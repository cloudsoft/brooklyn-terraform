package io.cloudsoft.terraform.entity;

import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.BasicConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.entity.stock.BasicEntity;
import org.apache.brooklyn.util.core.flags.SetFromFlag;

import java.util.Map;

public interface TerraformResource extends BasicEntity {

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @SetFromFlag("tfStateContents")
    ConfigKey<Map<String,Object>> STATE_CONTENTS = new BasicConfigKey( Map.class,
            "tf.state.contents",
            "Contents of the state of this resource as returned by Terrraform.");

    @SetFromFlag("type")
    ConfigKey<String> TYPE =  ConfigKeys.newStringConfigKey("tf.resource.type",
            "Terraform resource type (vsphere_virtual_machine, aws_instance, vpc, security group, etc)", null);

    @SetFromFlag("provider")
    ConfigKey<String> PROVIDER =  ConfigKeys.newStringConfigKey("tf.resource.provider",
            "Terraform resource provider (cloud provider)", null);

    @SetFromFlag("name")
    ConfigKey<String> NAME =  ConfigKeys.newStringConfigKey("tf.resource.name",
            "Terraform resource name.", null);

    @SetFromFlag("address")
    ConfigKey<String> ADDRESS =  ConfigKeys.newStringConfigKey("tf.resource.address",
            "Terraform resource address.", null);

    boolean refreshSensors(Map<String, Object> resource);
}
