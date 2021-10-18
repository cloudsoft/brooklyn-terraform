##Output - Output is anything you want to have displayed back to you on the console of the machine from which you are running Terraform.
output "ip" {
  value = "${vsphere_virtual_machine.vm01.*.default_ip_address}"
}
output "vm-moref" {
  value = "${vsphere_virtual_machine.vm01.moid}"
}