variable "vsphere_server" {}

variable "vsphere_user" {}

variable "vsphere_password" {}

variable "vsphere_datastore" {}

variable "vsphere_compute_cluster" {}

variable "vsphere_network" {}

variable "vsphere_virtual_machine" {}

variable "vm_user" {}

variable "vm_password" {}

variable "vm_name" {} # not used in this version

variable "tomcat_vm_name" {
    default = "Tomcat-VM"
}