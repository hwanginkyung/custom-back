resource "aws_security_group" "app" {
  count = var.enabled && var.manage_security_group ? 1 : 0

  name        = "${var.name}-sg"
  description = "Security group for ${var.name}"
  vpc_id      = var.vpc_id

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = var.ingress_cidrs_ssh
  }

  ingress {
    description = "App"
    from_port   = var.app_port
    to_port     = var.app_port
    protocol    = "tcp"
    cidr_blocks = var.ingress_cidrs_app
  }

  dynamic "ingress" {
    for_each = var.extra_ingress_tcp_ports
    content {
      description = "Extra-${ingress.value}"
      from_port   = ingress.value
      to_port     = ingress.value
      protocol    = "tcp"
      cidr_blocks = var.ingress_cidrs_app
    }
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, {
    Name = "${var.name}-sg"
  })

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_instance" "app" {
  count = var.enabled ? 1 : 0

  ami                         = var.ami_id
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_id
  key_name                    = var.key_name
  associate_public_ip_address = true

  vpc_security_group_ids = var.manage_security_group ? [aws_security_group.app[0].id] : var.security_group_ids

  root_block_device {
    volume_size = var.root_volume_size
    volume_type = "gp3"
    encrypted   = var.root_volume_encrypted
  }

  tags = merge(var.tags, {
    Name = var.name
    Role = "customs-app"
  })

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_eip" "app" {
  count = var.enabled && var.enable_eip ? 1 : 0

  domain   = "vpc"
  instance = aws_instance.app[0].id

  tags = merge(var.tags, {
    Name = "${var.name}-eip"
  })

  lifecycle {
    prevent_destroy = true
  }
}
