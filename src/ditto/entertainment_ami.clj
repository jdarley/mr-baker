(ns ditto.entertainment-ami
  (:require [cheshire.core :as json]
            [environ.core :refer [env]]
            [clj-time.core :as time-core]
            [clj-time.format :as time-format]))

;; TODO - make this file read more like the JSON schema

(defn shell [& cmds]
  {:type "shell"
   :execute_command "chmod +x {{ .Path }}; {{ .Vars }} sudo -E sh '{{ .Path }}'"
   :inline_shebang "/bin/sh -x"
   :inline cmds})

(defn ent-ami-name
  "Returns the ami name for date/time now"
  []
  (str "entertainment-base-" (time-format/unparse
                              (time-format/formatters :date-time-no-ms)
                              (time-core/now))))

(defn ebs-builder
  "Generate a new ami builder"
  [parent-ami]
  {:access_key (env :service-aws-access-key)
   :ami_name (ent-ami-name)
   :iam_instance_profile "baking"
   :instance_type "t1.micro"
   :region "eu-west-1"
   :secret_key (env :service-aws-secret-key)
   :security_group_id "sg-c453b4ab"
   :source_ami parent-ami
   :ssh_keypair_pattern "nokia-%s"
   :ssh_timeout "5m"
   :ssh_username "nokia"
   :subnet_id "subnet-bdc08fd5"
   :type "amazon-ebs"
   :vpc_id "vpc-7bc88713"})

(def upload-repo-file
  {:type "file"
   :source "ami-scripts/nokia-internal.repo"
   :destination "/tmp/nokia-internal.repo"})

(def append-repo-file
  (shell "cat /tmp/nokia-internal.repo >> /etc/yum.repos.d/nokia-internal.repo"
         "echo \"iam_role=1\" >> /etc/yum/pluginconf.d/nokia-s3yum.conf"))

(def enable-nokia-repo
  (shell "yum-config-manager --enable nokia-epel >> /var/log/baking.log 2>&1"))

(def puppet
  (shell "export LD_LIBRARY_PATH=/opt/rh/ruby193/root/usr/lib64"
         "PUPPETD=\"PATH=/opt/rh/ruby193/root/usr/local/bin/:/opt/rh/ruby193/root/usr/bin/:/sbin:/usr/sbin:/bin:/usr/bin /opt/rh/ruby193/root/usr/local/bin/puppet\""
         "yum install -y puppet >> /var/log/baking.log 2>&1"
         "scl enable ruby193 ' /opt/rh/ruby193/root/usr/local/bin/puppet agent --onetime --no-daemonize --server puppetaws.brislabs.com'"
         "rm -rf /var/lib/puppet/ssl"))

(def ruby-193
  (shell "yum install -y ruby193"
         "yum install -y ruby193-rubygem-puppet"
         "yum install -y ruby193-rubygem-ruby-shadow"))

(defn motd [parent-ami]
  (shell "echo -e \"\\nEntertainment Base AMI\" >> /etc/motd"
         (format "echo -e \"\\nBake date: %s\" >> /etc/motd"
                 (time-format/unparse (time-format/formatters :date-time-no-ms) (time-core/now)))
         (format "echo -e \"\\nSource AMI: %s\\n\" >> /etc/motd" parent-ami)))

(def cloud-final
  (shell "chkconfig cloud-final off"
         "sudo sed -i \"s/# chkconfig:   - 99 01/# chkconfig:   - 98 01/\" /etc/rc.d/init.d/cloud-final"
         "chkconfig cloud-final on"))


(defn ami-template
  "Generate a new ami template"
  [parent-ami]
  {:builders [(ebs-builder parent-ami)]
   :provisioners [(motd parent-ami)
                  upload-repo-file
                  append-repo-file
                  enable-nokia-repo
                  ruby-193
                  puppet
                  cloud-final]})

(defn create-base-ami
  "Creates a new entertainment base-ami from the parent ami id"
  [parent-ami]
  (json/generate-string (ami-template parent-ami)))
