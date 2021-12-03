package io.cloudsoft.terraform.entity;

import org.apache.brooklyn.api.entity.ImplementedBy;

@ImplementedBy(DataResourceImpl.class)
public interface DataResource extends TerraformResource {
}
