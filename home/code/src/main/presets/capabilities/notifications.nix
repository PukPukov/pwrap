# Notification capability, never full access to the daemon's other interfaces.
let
  endpoint = {
    destination = "org.freedesktop.Notifications";
    interface = "org.freedesktop.Notifications";
    path = "/org/freedesktop/Notifications";
  };
in {
  calls = [ (endpoint // {
    members = [ "GetCapabilities" "GetServerInformation" "Notify" "CloseNotification" ];
  }) ];
  broadcasts = [ (endpoint // {
    members = [ "ActionInvoked" "NotificationClosed" "ActivationToken" ];
  }) ];
}
