##Output - Output is anything you want to have displayed back to you on the console of the machine from which you are running Terraform.
output "tomcat_ip" {
  value = vsphere_virtual_machine.tomcatVM.*.default_ip_address
}

output "main_uri" {
  value = "http://${vsphere_virtual_machine.tomcatVM.default_ip_address}:8080"
}

output "database_ip" {
  value = vsphere_virtual_machine.mysqlVM.*.default_ip_address
}

output "db_main_uri" {
  value = "mysql://${vsphere_virtual_machine.mysqlVM.default_ip_address}:3306"
}
