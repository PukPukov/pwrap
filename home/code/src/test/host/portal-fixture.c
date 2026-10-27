/* Test-only standard portal peer on a private bus. Never invokes a handler/UI. */
#include <gio/gio.h>
#include <gio/gunixfdlist.h>
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>

static const char *output;
static char **handler;
static const char *xml =
  "<node><interface name='org.freedesktop.portal.OpenURI'>"
  "<method name='OpenURI'><arg type='s' direction='in'/><arg type='s' direction='in'/>"
  "<arg type='a{sv}' direction='in'/><arg type='o' direction='out'/></method>"
  "<method name='OpenFile'><arg type='s' direction='in'/><arg type='h' direction='in'/>"
  "<arg type='a{sv}' direction='in'/><arg type='o' direction='out'/></method>"
  "</interface></node>";

static void
called (GDBusConnection *bus, const char *sender, const char *path G_GNUC_UNUSED,
        const char *iface G_GNUC_UNUSED, const char *member, GVariant *params,
        GDBusMethodInvocation *invocation, gpointer unused G_GNUC_UNUSED)
{
  g_autoptr(GError) error = NULL;
  g_autoptr(GVariant) options = NULL;
  g_autofree char *component = g_strdup (sender + 1);
  g_autofree char *request = NULL;
  g_autofree char *log = g_build_filename (output, "calls", NULL);
  const char *parent, *token, *uri = "";
  gboolean ask = FALSE;
  int index;
  if (g_str_equal (member, "OpenFile"))
    {
      char contents[32] = {0};
      GUnixFDList *fds = g_dbus_message_get_unix_fd_list (g_dbus_method_invocation_get_message (invocation));
      g_variant_get (params, "(&sh@a{sv})", &parent, &index, &options);
      int fd = g_unix_fd_list_get (fds, index, &error);
      g_assert_no_error (error);
      g_assert_cmpint (fcntl (fd, F_GETFL) & O_ACCMODE, ==, O_RDONLY);
      g_assert_cmpint (pread (fd, contents, sizeof contents, 0), ==, 13);
      g_assert_cmpstr (contents, ==, "owned fixture");
      close (fd);
    }
  else
    g_variant_get (params, "(&s&s@a{sv})", &parent, &uri, &options);
  if (handler != NULL)
    {
      guint length = g_strv_length (handler);
      g_auto(GStrv) command = g_new0 (char *, length + 2);
      for (guint i = 0; i < length; i++) command[i] = g_strdup (handler[i]);
      command[length] = g_strdup (uri);
      g_autoptr(GSubprocess) process = g_subprocess_newv ((const char * const *) command,
        G_SUBPROCESS_FLAGS_NONE, &error);
      g_assert_no_error (error);
      g_assert_true (g_subprocess_wait_check (process, NULL, &error));
      g_assert_no_error (error);
    }
  g_assert_true (g_variant_lookup (options, "handle_token", "&s", &token));
  g_variant_lookup (options, "ask", "b", &ask);
  FILE *file = fopen (log, "a");
  g_assert_nonnull (file);
  fprintf (file, "%s ask=%s %s\n", member, ask ? "true" : "false", uri);
  g_assert_cmpint (fclose (file), ==, 0);
  g_strdelimit (component, ".", '_');
  request = g_strdup_printf ("/org/freedesktop/portal/desktop/request/%s/%s", component, token);
  g_dbus_method_invocation_return_value (invocation, g_variant_new ("(o)", request));
  g_dbus_connection_emit_signal (bus, sender, request, "org.freedesktop.portal.Request", "Response",
    g_variant_new ("(ua{sv})", 0, NULL), &error);
  g_assert_no_error (error);
}

int
main (int argc, char **argv)
{
  g_autoptr(GError) error = NULL;
  g_autoptr(GDBusConnection) bus = NULL;
  g_autoptr(GDBusNodeInfo) info = NULL;
  g_autoptr(GVariant) reply = NULL;
  g_autoptr(GMainLoop) loop = NULL;
  g_autofree char *ready = NULL;
  static const GDBusInterfaceVTable methods = { .method_call = called };
  g_assert_cmpint (argc, >=, 3);
  output = argv[2];
  if (argc > 3) handler = argv + 3;
  bus = g_dbus_connection_new_for_address_sync (argv[1],
    G_DBUS_CONNECTION_FLAGS_AUTHENTICATION_CLIENT | G_DBUS_CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION,
    NULL, NULL, &error);
  g_assert_no_error (error);
  info = g_dbus_node_info_new_for_xml (xml, &error);
  g_assert_no_error (error);
  g_assert_cmpuint (g_dbus_connection_register_object (bus, "/org/freedesktop/portal/desktop",
    info->interfaces[0], &methods, NULL, NULL, &error), !=, 0);
  g_assert_no_error (error);
  reply = g_dbus_connection_call_sync (bus, "org.freedesktop.DBus", "/org/freedesktop/DBus",
    "org.freedesktop.DBus", "RequestName", g_variant_new ("(su)", "org.freedesktop.portal.Desktop", 4),
    G_VARIANT_TYPE ("(u)"), G_DBUS_CALL_FLAGS_NONE, 5000, NULL, &error);
  g_assert_no_error (error);
  ready = g_build_filename (output, "ready", NULL);
  g_assert_true (g_file_set_contents (ready, "ready", -1, &error));
  g_assert_no_error (error);
  loop = g_main_loop_new (NULL, FALSE);
  g_main_loop_run (loop);
  return 0;
}
