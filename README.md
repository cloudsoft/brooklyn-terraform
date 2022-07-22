
This project provides an [Apache Brooklyn](https://brooklyn.apache.org/) entity for management of Terraform configuration.
[Terraform](https://terraform.io/) is a tool for building, changing, and versioning infrastructure safely and efficiently.

## Usage

See [the docs/ folder](docs/) for info on how to use this.

**Note** If you installed the project with `catalog.bom` then you can use the entity by using type
`terraform`. If you installed just the classes manually then you should refer to the Java type
`io.cloudsoft.terraform.TerraformConfiguration` instead. Examples in thew docs/ refer to `terraform`.


## Build

Clone the project then `cd` to the newly created repository and run:

```shell
mvn clean install
```

## Install

Use the [Brooklyn CLI](https://brooklyn.apache.org/download/index.html#command-line-client) to add the resulting bundle to the catalog(or import it from the GUI):

```shell
br catalog add target/brooklyn-terraform-1.1.0-SNAPSHOT.jar
```

Alternatively, for quick tests, copy the latest jar to the Brooklyn distribution's `dropins` directory
and then launch (or restart) Brooklyn.

```shell
    wget -O brooklyn-terraform-1.1.0-SNAPSHOT.jar "https://oss.sonatype.org/service/local/artifact/maven/redirect?r=snapshots&g=io.cloudsoft.terraform&a=brooklyn-terraform&v=1.1.0-SNAPSHOT&e=jar"
    mv brooklyn-terraform-1.1.0-SNAPSHOT.jar $BROOKLYN_HOME/lib/dropins/
    nohup $BROOKLYN_HOME/bin/brooklyn launch &
```
