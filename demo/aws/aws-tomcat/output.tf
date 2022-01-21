##Output - Output is anything you want to have displayed back to you on the console of the machine from which you are running Terraform.
output "tomcat_ip" {
  value = aws_instance.ubuntu-tomcat.*.public_ip
}

output "main_uri" {
  value = "http://${aws_eip.ubuntu-tomcat.public_dns}:8080"
}

output "database_ip" {
  value = aws_instance.ubuntu-mysql.*.public_ip
}

output "db_main_uri" {
  value = "mysql://${aws_eip.ubuntu-mysql.public_ip}:3306"
}
