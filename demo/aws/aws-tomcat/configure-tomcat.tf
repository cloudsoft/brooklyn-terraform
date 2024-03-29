resource "aws_security_group" "ubuntu-tomcat" {
  name        = "test-ubuntu-tomcat-security-group"
  description = "Allow HTTP, HTTPS and SSH traffic"

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTP"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.tomcat_vm_name}-sg"
  }
}


resource "aws_instance" "ubuntu-tomcat" {
  key_name      = aws_key_pair.ubuntu.key_name
  ami           = var.ami_id
  instance_type = var.ec2_type
  #  num_cpus = 2
  #  memory   = 4096

  tags = {
    Name = var.tomcat_vm_name
  }

  vpc_security_group_ids = [
    aws_security_group.ubuntu-tomcat.id
  ]

  connection {
    type        = "ssh"
    user        = "ubuntu"
    private_key = file("key")
    host        = self.public_ip
  }

/*  ebs_block_device {
    device_name = "/dev/sda1"
    volume_type = "gp2"
    volume_size = 30
  }*/

  provisioner "file" {
    source      = "./tomcat-scripts/"
    destination = "/tmp/"

    connection {
      type        = "ssh"
      user        = "ubuntu"
      private_key = file("key")
      host        = self.public_ip
    }
  }

  # Execute script on remote vm after this creation
  provisioner "remote-exec" {
    inline = [
      "chmod +x /tmp/*sh",
      "sudo /tmp/tomcat-create.sh",
      "sudo /tmp/tomcat-configure.sh ${aws_eip.ubuntu-mysql.public_ip}",
      "sudo /tmp/tomcat-start.sh",
      "echo ${self.public_ip}",
    ]

    connection {
      agent       = false
      type        = "ssh"
      user        = "ubuntu"
      private_key = file("key")
      host        = self.public_ip
    }
  }

  depends_on = [
    aws_instance.ubuntu-mysql
  ]
}

# Provides an Elastic IP resource.
resource "aws_eip" "ubuntu-tomcat" {
  vpc      = true
  instance = aws_instance.ubuntu-tomcat.id

  tags = {
    Name = "${var.tomcat_vm_name}-eip"
  }
}
