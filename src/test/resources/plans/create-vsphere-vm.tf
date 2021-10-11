provider "vsphere" {
    user           = "administrator@vsphere.local"
    password       = "A@M30!bTyyS01G"
    vsphere_server = "https://vcenter.dns-test.cloudsoftdev.net/sdk"

    # If you have a self-signed cert
    allow_unverified_ssl = true
}

resource "vsphere_virtual_machine" "vm-example1" {
    name             = "terraform-test"
    resource_pool_id = "Resources"
    datastore_id     = "esx41-SSD1"
    image_id         = "cloudsoft-ubuntu-20.04-with-deps-template"
    user             = "cloudsoft"
    password         = "7ThalesUser!"

    network_interface {
      network_id = " Public 4020 esx41"
    }
}