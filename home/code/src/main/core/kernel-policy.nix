# Shared family vocabulary, not a merger of service and launcher isolation.
let addressFamilies = [ "AF_UNIX" "AF_INET" "AF_INET6" ];
in {
  inherit addressFamilies;
  # glibc interface discovery and network-control workloads need netlink.
  networkControlFamilies = addressFamilies ++ [ "AF_NETLINK" ];
}
