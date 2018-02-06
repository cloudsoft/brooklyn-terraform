---
title_in_menu: Terraform
title: Brooklyn Terraform Integration
layout: website-normal
---

This project provides an [Apache Brooklyn](https://brooklyn.apache.org/) entity for management of Terraform configuration.
[Terraform](https://terraform.io/) is a tool for building, changing, and versioning infrastructure safely and efficiently.


## Build

Clone the project then `cd` to the newly created repository and run:

    mvn clean install


## Install

Use the [Brooklyn CLI](https://brooklyn.apache.org/download/index.html#command-line-client) to
add `catalog.bom` to an existing server.

    br catalog add https://raw.githubusercontent.com/cloudsoft/brooklyn-terraform/master/catalog.bom

Alternatively, for quick tests, copy the latest jar to the Brooklyn distribution's `dropins` directory
and then launch (or restart) Brooklyn.

    wget -O brooklyn-terraform-0.12.0-SNAPSHOT.jar "https://oss.sonatype.org/service/local/artifact/maven/redirect?r=snapshots&g=io.cloudsoft.terraform&a=brooklyn-terraform&v=0.12.0-SNAPSHOT&e=jar"
    mv brooklyn-terraform-0.12.0-SNAPSHOT.jar $BROOKLYN_HOME/lib/dropins/
    nohup $BROOKLYN_HOME/bin/brooklyn launch &


## Use

**Note** If you installed the project with `catalog.bom` then you can use the entity by using type
`terraform`. If you installed the dependencies manually then you should refer to the Java type
`io.cloudsoft.terraform.TerraformConfiguration` instead. Examples below refer to `terraform`.

The entity requires a value for one of the `tf.configuration.contents` and `tf.configuration.url`
cofiguration keys. The former allows you to include a plan directly in a blueprint. The latter
has the entity load a remote resource at runtime. The resource must be accessible to the Brooklyn
server.

When started the entity installs Terraform and applies the configured plan. For example, to run
Terraform on localhost with a plan that provisions an instance in Amazon EC2 us-east-1 and assigns
it an elastic IP:

    location: localhost
    name: Brooklyn Terraform Deployment
    services:
    - type: terraform
      name: Terraform Configuration
      brooklyn.config:
        tf.configuration.contents: |
            provider "aws" {
                access_key = "YOUR_ACCESS_KEY"
                secret_key = "YOUR_SECRET_KEY"
                region = "us-east-1"
            }

            resource "aws_instance" "example" {
                ami = "ami-408c7f28"
                instance_type = "t1.micro"
                tags {
                    Name = "brooklyn-terraform-test"
                }
            }

            resource "aws_eip" "ip" {
                instance = "${aws_instance.example.id}"
            }

Instructions are given in the Brooklyn documentation for
[configuring localhost as a location](https://brooklyn.apache.org/v/latest/locations/index.html#localhost).

The Terraform plan's [outputs](https://www.terraform.io/intro/getting-started/outputs.html)
are published as Brooklyn sensors prefixed with `tf.output`. Use this to communicate
information about the infrastructure created by Terraform to other components of the
blueprint via Brooklyn's [dependent configuration](https://brooklyn.apache.org/v/0.11.0/yaml/yaml-reference.html#dsl-commands).

For example, to attach a `TomcatServer` to an AWS security group that was created by
a Terraform plan:

    services:

    - type: org.apache.brooklyn.entity.webapp.tomcat.TomcatServer
      location:
        jclouds:aws-ec2:us-west-2:
          osFamily: centos
          templateOptions:
            securityGroupIds:
            - $brooklyn:component("tf").attributeWhenReady("tf.output.securityGroupId")
      brooklyn.config:
        launch.latch: $brooklyn:component("tf").attributeWhenReady("service.isUp")
        wars.root: http://search.maven.org/remotecontent?filepath=org/apache/brooklyn/example/brooklyn-example-hello-world-webapp/0.9.0/brooklyn-example-hello-world-webapp-0.9.0.war

    - type: terraform
      id: tf
      location: localhost
      brooklyn.config:
        tf.configuration.contents: |
            # Credentials are given here for a self-contained blueprint. In practice you
            # would inject the values with an external configuration provider.
            provider "aws" {
                access_key = "..."
                secret_key = "..."
                region = "us-west-2"
            }

            resource "aws_security_group" "allow_all" {
              description = "test-security-group allowing all access"

              ingress {
                from_port = 0
                to_port = 0
                protocol = "-1"
                cidr_blocks = ["0.0.0.0/0"]
              }

              egress {
                from_port = 0
                to_port = 0
                protocol = "-1"
                cidr_blocks = ["0.0.0.0/0"]
              }
            }

            output "securityGroupId" {
                value = "${aws_security_group.allow_all.id}"
            }

Keep credentials out of your blueprint by using Brooklyn's
[external configuration providers](https://brooklyn.apache.org/v/latest/ops/externalized-configuration.html).
For example, rather than including the `provider` block in the example above,
you might write:

    type: terraform
    brooklyn.config:
      aws.identity: $brooklyn:external("terraform", "aws.identity")
      aws.credential: $brooklyn:external("terraform", "aws.credential")

      shell.env:
        AWS_ACCESS_KEY_ID: $brooklyn:config("aws.identity")
        AWS_SECRET_ACCESS_KEY: $brooklyn:config("aws.credential")
        AWS_DEFAULT_REGION: $brooklyn:config("aws.region")

And configure the `terraform` provider in `brooklyn.properties`:

    # Refer to the Brooklyn docs for information on other kind of suppliers.
    brooklyn.external.terraform=org.apache.brooklyn.core.config.external.InPlaceExternalConfigSupplier
    brooklyn.external.terraform.aws.identity=...
    brooklyn.external.terraform.aws.credential=...

## Build Docker image

```bash
export MAJOR=0.12.0
export RC_NUMBER=rc3
export VERSION=${MAJOR}-${RC_NUMBER}

export BROOKLYN_ARTIFACT_URL=https://dist.apache.org/repos/dist/dev/brooklyn/apache-brooklyn-${VERSION}/apache-brooklyn-${VERSION}-1.noarch.rpm
docker build -t brooklyn-terraform:latest --build-arg AMP_RPM_URL=${BROOKLYN_ARTIFACT_URL} \
--build-arg ADDITIONAL_BOMS=https://raw.githubusercontent.com/andreaturli/brooklyn-terraform/master/catalog.bom \
--build-arg DROPINS_JARS="https://oss.sonatype.org/service/local/artifact/maven/redirect?r=snapshots&g=io.cloudsoft.terraform&a=brooklyn-terraform&v=0.12.0-SNAPSHOT&e=jar" \ 
.
```

### Run Docker Image

```bash
docker run --rm -it -P brooklyn-terraform
```

-----

Copyright 2014-2016 by Cloudsoft Corporation Limited

> Licensed under the Apache License, Version 2.0 (the "License");
> you may not use this file except in compliance with the License.
> You may obtain a copy of the License at
>
> http://www.apache.org/licenses/LICENSE-2.0
>
> Unless required by applicable law or agreed to in writing, software
> distributed under the License is distributed on an "AS IS" BASIS,
> WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
> See the License for the specific language governing permissions and
> limitations under the License.
