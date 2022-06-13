package io.cloudsoft.terraform;

import org.apache.brooklyn.api.catalog.Catalog;
import org.apache.brooklyn.api.entity.ImplementedBy;

@Catalog(
        name = "TerraformEntity",
        description = "Brooklyn Terraform Entity with Custom Effectors",
        iconUrl = "classpath://io/cloudsoft/terraform/logo.png")
@ImplementedBy(TerraformEntityImpl.class)
public interface TerraformEntity {
}
