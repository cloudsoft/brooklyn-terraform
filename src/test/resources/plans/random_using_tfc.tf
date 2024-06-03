terraform {
  cloud {
    organization = "Cutover_Demo_Org"

    workspaces {
      name = "Inventory-Forecasting"
    }
  }
}

resource "random_string" "random" {
  length = var.length
  numeric = false
  upper = false
  special = false
}

variable "length" {
  default = 6
}

output "r" {
  value = random_string.random.id
}
