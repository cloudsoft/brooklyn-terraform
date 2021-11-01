resource "vsphere_tag" "server_tag" {
  name        = "terraform-test-app-server"
  category_id = vsphere_tag_category.category.id
  description = "App Server managed by Terraform"
}

##vSphere VMs - This is the section where we actually do the cloning of the virtual machine

resource "vsphere_virtual_machine" "tomcatVM" {
  name             = var.tomcat_vm_name
  resource_pool_id = data.vsphere_resource_pool.pool.id
  datastore_id     = data.vsphere_datastore.datastore.id
  guest_id = data.vsphere_virtual_machine.template.guest_id
  scsi_type = data.vsphere_virtual_machine.template.scsi_type
  folder = vsphere_folder.tf_folder.path
  num_cpus = 2
  memory   = 4096
  tags = [vsphere_tag.server_tag.id]


  network_interface {
    network_id   = data.vsphere_network.network.id
  }

  disk {
    label = "tomcat-vm.vmdk"
    size = "30"
  }

  clone {
    template_uuid = data.vsphere_virtual_machine.template.id
  }

  provisioner "file" {
    source = "./tomcat-scripts/"
    destination = "/tmp/"

    connection {
      agent = false
      type     = "ssh"
      user     = var.vm_user
      password = var.vm_password
      host     = vsphere_virtual_machine.tomcatVM.default_ip_address
    }
  }

  # Execute script on remote vm after this creation
  provisioner "remote-exec" {
    inline = [
      "chmod +x /tmp/*sh",
      "sudo /tmp/tomcat-create.sh",
      "sudo /tmp/tomcat-configure.sh ${vsphere_virtual_machine.mysqlVM.default_ip_address}",
      "sudo /tmp/tomcat-start.sh",
      "echo ${self.default_ip_address}",
    ]

    connection {
      agent = false
      type     = "ssh"
      user     = var.vm_user
      password = var.vm_password
      host     = vsphere_virtual_machine.tomcatVM.default_ip_address
    }
  }

  depends_on = [
    vsphere_folder.tf_folder, vsphere_virtual_machine.mysqlVM
  ]
}
