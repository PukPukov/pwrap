/* Included in upstream test-proxy.c: reuse its private bus, real subprocess,
 * authentication, readiness and teardown. No host portal or user files touched. */
#include <gio/gunixfdlist.h>

#define PORTAL_PATH "/org/freedesktop/portal/desktop"
#define PORTAL_IFACE "org.freedesktop.portal.OpenURI"
#define PORTAL_POLICY "--pwrap-portal-open=http,https,tg,file"
static const char *default_policy[] = { PORTAL_POLICY, NULL, NULL };
static const char *opposite_policy[] = { PORTAL_POLICY, "--pwrap-portal-file-ask=false", "--pwrap-portal-uri-ask=true" };
static const char *restricted_policy[] = { "--pwrap-portal-open=https", NULL, NULL };
static const char *empty_policy[] = { "--pwrap-portal-open=", NULL, NULL };

/* A benign fake portal echoes what actually crossed the process/bus boundary,
 * including ancillary FDs and wire metadata, not a helper's intermediate state. */
static GDBusMessage *
portal_echo (GDBusConnection *connection, GDBusMessage *message,
             gboolean incoming, gpointer unused G_GNUC_UNUSED)
{
  g_autoptr(GDBusMessage) reply = NULL;
  g_autoptr(GError) error = NULL;
  if (!incoming || g_dbus_message_get_message_type (message) != G_DBUS_MESSAGE_TYPE_METHOD_CALL ||
      g_strcmp0 (g_dbus_message_get_path (message), PORTAL_PATH) != 0)
    return message;
  reply = g_dbus_message_new_method_reply (message);
  g_dbus_message_set_body (reply, g_variant_new ("(vuuu)",
    g_dbus_message_get_body (message), g_dbus_message_get_serial (message),
    g_dbus_message_get_byte_order (message), g_dbus_message_get_flags (message)));
  g_dbus_message_set_unix_fd_list (reply, g_dbus_message_get_unix_fd_list (message));
  g_dbus_connection_send_message (connection, reply, G_DBUS_SEND_MESSAGE_FLAGS_NONE, NULL, &error);
  g_assert_no_error (error);
  g_object_unref (message);
  return NULL;
}

static GDBusMessage *
portal_call (Fixture *f, const char *destination, const char *interface,
             const char *member, GVariant *body, GUnixFDList *fds,
             GDBusMessageByteOrder order)
{
  g_autoptr(GError) error = NULL;
  g_autoptr(GDBusMessage) call = g_dbus_message_new_method_call (destination, PORTAL_PATH, interface, member);
  GDBusMessage *reply;
  guint32 serial;
  g_dbus_message_set_byte_order (call, order);
  g_dbus_message_set_flags (call, G_DBUS_MESSAGE_FLAGS_NO_AUTO_START);
  g_dbus_message_set_body (call, body);
  if (fds != NULL)
    g_dbus_message_set_unix_fd_list (call, fds);
  reply = g_dbus_connection_send_message_with_reply_sync (f->proxied.conn, call,
    G_DBUS_SEND_MESSAGE_FLAGS_NONE, 5000, &serial, NULL, &error);
  g_assert_no_error (error);
  g_assert_nonnull (reply);
  g_assert_cmpuint (g_dbus_message_get_reply_serial (reply), ==, serial);
  if (g_dbus_message_get_message_type (reply) == G_DBUS_MESSAGE_TYPE_METHOD_RETURN)
    {
      guint32 observed_serial, observed_order, observed_flags;
      g_autoptr(GVariant) observed = NULL;
      g_variant_get (g_dbus_message_get_body (reply), "(@vuuu)", &observed,
                     &observed_serial, &observed_order, &observed_flags);
      g_assert_cmpuint (observed_serial, ==, serial);
      g_assert_cmpuint (observed_order, ==, order);
      g_assert_cmpuint (observed_flags, ==, G_DBUS_MESSAGE_FLAGS_NO_AUTO_START);
    }
  return reply;
}

static GVariant *
portal_options (int ask)
{
  GVariantBuilder options;
  g_variant_builder_init (&options, G_VARIANT_TYPE_VARDICT);
  if (ask >= 0)
    g_variant_builder_add (&options, "{sv}", "ask",
      ask == 2 ? g_variant_new_string ("false") : g_variant_new_boolean (ask != 0));
  if (ask == 3)
    {
      g_variant_builder_add (&options, "{sv}", "ask", g_variant_new_boolean (TRUE));
      g_variant_builder_add (&options, "{sv}", "ask", g_variant_new_boolean (FALSE));
    }
  g_variant_builder_add (&options, "{sv}", "handle_token", g_variant_new_string ("pwrap_fixture"));
  g_variant_builder_add (&options, "{sv}", "activation_token", g_variant_new_string ("fixture-token"));
  g_variant_builder_add (&options, "{sv}", "writable", g_variant_new_boolean (FALSE));
  return g_variant_builder_end (&options);
}

static GVariant *
portal_result_body (GDBusMessage *reply)
{
  g_autoptr(GVariant) boxed = g_variant_get_child_value (g_dbus_message_get_body (reply), 0);
  g_assert_cmpint (g_dbus_message_get_message_type (reply), ==, G_DBUS_MESSAGE_TYPE_METHOD_RETURN);
  return g_variant_get_variant (boxed);
}

static void
test_portal_file (Fixture *f, gconstpointer context G_GNUC_UNUSED)
{
  g_autoptr(GError) error = NULL;
  g_autofree char *path = g_build_filename (f->temp_directory, "fixture.txt", NULL);
  g_autoptr(GUnixFDList) fds = g_unix_fd_list_new ();
  guint filter = g_dbus_connection_add_filter (f->can_talk_conn.conn, portal_echo, NULL, NULL);
  int fd;
  alarm (30);
  g_file_set_contents (path, "owned fixture", -1, &error);
  g_assert_no_error (error);
  fd = open (path, O_RDONLY | O_CLOEXEC);
  g_assert_cmpint (fd, >=, 0);
  /* Index one exercises preservation, rather than assuming h always means 0. */
  g_assert_cmpint (g_unix_fd_list_append (fds, fd, &error), ==, 0);
  g_assert_no_error (error);
  g_assert_cmpint (g_unix_fd_list_append (fds, fd, &error), ==, 1);
  g_assert_no_error (error);
  close (fd);
  fixture_start_proxy (f);
  for (int ask = -1; ask <= 3; ask++)
    for (guint variant = 0; variant < 8; variant++)
      {
        GDBusMessageByteOrder order = variant & 1 ? G_DBUS_MESSAGE_BYTE_ORDER_BIG_ENDIAN : G_DBUS_MESSAGE_BYTE_ORDER_LITTLE_ENDIAN;
        g_autoptr(GDBusMessage) reply = portal_call (f,
          variant & 2 ? f->can_talk_conn.unique_name : CAN_TALK_NAME,
          variant & 4 ? NULL : PORTAL_IFACE, "OpenFile",
          g_variant_new ("(sh@a{sv})", "wayland:fixture", 1, portal_options (ask)), fds, order);
        g_autoptr(GVariant) body = portal_result_body (reply);
        g_autoptr(GVariant) options = NULL;
        const char *parent, *token;
        gboolean asked, writable;
        int index, received;
        char contents[32] = {0};
        guint ask_count = 0;
        g_variant_get (body, "(&sh@a{sv})", &parent, &index, &options);
        g_assert_cmpstr (parent, ==, "wayland:fixture");
        g_assert_cmpint (index, ==, 1);
        g_assert_true (g_variant_lookup (options, "ask", "b", &asked));
        g_assert_cmpint (asked, ==, g_strcmp0 (f->portal_file_ask, "--pwrap-portal-file-ask=false") != 0);
        g_assert_true (g_variant_lookup (options, "writable", "b", &writable));
        g_assert_false (writable);
        g_assert_true (g_variant_lookup (options, "handle_token", "&s", &token));
        g_assert_cmpstr (token, ==, "pwrap_fixture");
        g_assert_true (g_variant_lookup (options, "activation_token", "&s", &token));
        g_assert_cmpstr (token, ==, "fixture-token");
        for (gsize i = 0; i < g_variant_n_children (options); i++)
          {
            g_autoptr(GVariant) entry = g_variant_get_child_value (options, i);
            g_autoptr(GVariant) key = g_variant_get_child_value (entry, 0);
            ask_count += g_str_equal (g_variant_get_string (key, NULL), "ask");
          }
        g_assert_cmpuint (ask_count, ==, 1);
        g_assert_cmpint (g_unix_fd_list_get_length (g_dbus_message_get_unix_fd_list (reply)), ==, 2);
        received = g_unix_fd_list_get (g_dbus_message_get_unix_fd_list (reply), index, &error);
        g_assert_no_error (error);
        g_assert_cmpint (pread (received, contents, sizeof contents, 0), ==, 13);
        g_assert_cmpstr (contents, ==, "owned fixture");
        close (received);
      }
  g_dbus_connection_remove_filter (f->can_talk_conn.conn, filter);
  g_unlink (path);
}

static void
test_portal_uri (Fixture *f, gconstpointer context G_GNUC_UNUSED)
{
  const char *uris[] = { "https://example.invalid/fixture", "HTTP://example.invalid/", "tg://resolve?domain=fixture" };
  guint filter = g_dbus_connection_add_filter (f->can_talk_conn.conn, portal_echo, NULL, NULL);
  alarm (30);
  fixture_start_proxy (f);
  for (guint i = 0; i < G_N_ELEMENTS (uris); i++)
    for (guint variant = 0; variant < 8; variant++)
    for (int caller_ask = -1; caller_ask <= 3; caller_ask++)
    {
      g_autoptr(GDBusMessage) reply = portal_call (f,
        variant & 2 ? f->can_talk_conn.unique_name : CAN_TALK_NAME,
        variant & 4 ? NULL : PORTAL_IFACE, "OpenURI",
        g_variant_new ("(ss@a{sv})", "", uris[i], portal_options (caller_ask)), NULL,
        variant & 1 ? G_DBUS_MESSAGE_BYTE_ORDER_BIG_ENDIAN : G_DBUS_MESSAGE_BYTE_ORDER_LITTLE_ENDIAN);
      g_autoptr(GVariant) body = portal_result_body (reply);
      g_autoptr(GVariant) options = NULL;
      const char *parent, *uri;
      gboolean ask;
      g_variant_get (body, "(&s&s@a{sv})", &parent, &uri, &options);
      g_assert_cmpstr (uri, ==, uris[i]);
      g_assert_true (g_variant_lookup (options, "ask", "b", &ask));
      g_assert_cmpint (ask, ==, g_strcmp0 (f->portal_uri_ask, "--pwrap-portal-uri-ask=true") == 0);
    }
  g_dbus_connection_remove_filter (f->can_talk_conn.conn, filter);
}

static void
test_portal_denied (Fixture *f, gconstpointer context G_GNUC_UNUSED)
{
  const char *uris[] = { "file:///tmp/pwrap-fixture", "FILE:///tmp/pwrap-fixture", "about:blank", "tg:", "https:relative", "https://", "https://[", "/tmp/pwrap-fixture", "https://example.invalid/\nfixture" };
  g_autoptr(GError) error = NULL;
  g_autoptr(GUnixFDList) fds = g_unix_fd_list_new ();
  g_autofree char *oversized = g_strnfill (70000, 'a');
  int fd = open ("/dev/null", O_RDONLY | O_CLOEXEC);
  g_assert_cmpint (fd, >=, 0);
  g_unix_fd_list_append (fds, fd, &error);
  g_assert_no_error (error);
  close (fd);
  alarm (30);
  fixture_start_proxy (f);
  for (guint i = 0; i < G_N_ELEMENTS (uris) + 4; i++)
    {
      const char *method = "OpenURI";
      GVariant *body;
      if (i < G_N_ELEMENTS (uris))
        body = g_variant_new ("(ss@a{sv})", "", uris[i], portal_options (0));
      else if (i == G_N_ELEMENTS (uris))
        body = g_variant_new ("(s)", "wrong signature");
      else
        {
          method = i == G_N_ELEMENTS (uris) + 1 ? "OpenDirectory" : "OpenFile";
          body = g_variant_new ("(sh@a{sv})", "", i == G_N_ELEMENTS (uris) + 3 ? -1 : 0, portal_options (0));
        }
      g_autoptr(GDBusMessage) reply = portal_call (f,
        i & 1 ? f->can_talk_conn.unique_name : CAN_TALK_NAME,
        i & 1 ? NULL : PORTAL_IFACE, method, body, NULL,
        G_DBUS_MESSAGE_BYTE_ORDER_BIG_ENDIAN);
      g_assert_cmpstr (g_dbus_message_get_error_name (reply), ==, "org.freedesktop.DBus.Error.AccessDenied");
    }
  /* The argument filter must never grant access hidden by normal D-Bus rules. */
  g_autoptr(GDBusMessage) reply = portal_call (f, CAN_SEE_NAME, PORTAL_IFACE, "OpenURI",
    g_variant_new ("(ss@a{sv})", "", "https://example.invalid/", portal_options (0)), NULL,
    G_DBUS_MESSAGE_BYTE_ORDER_LITTLE_ENDIAN);
  g_assert_cmpstr (g_dbus_message_get_error_name (reply), ==, "org.freedesktop.DBus.Error.AccessDenied");
  for (int index = -1; index <= 1; index += 2)
    {
      g_autoptr(GDBusMessage) bad_index = portal_call (f, CAN_TALK_NAME, PORTAL_IFACE, "OpenFile",
        g_variant_new ("(sh@a{sv})", "", index, portal_options (0)), fds,
        G_DBUS_MESSAGE_BYTE_ORDER_LITTLE_ENDIAN);
      g_assert_cmpstr (g_dbus_message_get_error_name (bad_index), ==, "org.freedesktop.DBus.Error.AccessDenied");
    }
  g_autoptr(GDBusMessage) too_big = portal_call (f, CAN_TALK_NAME, PORTAL_IFACE, "OpenFile",
    g_variant_new ("(sh@a{sv})", oversized, 0, portal_options (0)), fds,
    G_DBUS_MESSAGE_BYTE_ORDER_LITTLE_ENDIAN);
  g_assert_cmpstr (g_dbus_message_get_error_name (too_big), ==, "org.freedesktop.DBus.Error.AccessDenied");
}

static void
test_portal_restricted (Fixture *f, gconstpointer context G_GNUC_UNUSED)
{
  g_autoptr(GError) error = NULL;
  g_autoptr(GUnixFDList) fds = g_unix_fd_list_new ();
  int fd = open ("/dev/null", O_RDONLY | O_CLOEXEC);
  g_assert_cmpint (fd, >=, 0);
  g_unix_fd_list_append (fds, fd, &error);
  g_assert_no_error (error);
  close (fd);
  alarm (30);
  fixture_start_proxy (f);
  g_autoptr(GDBusMessage) file = portal_call (f, CAN_TALK_NAME, PORTAL_IFACE, "OpenFile",
    g_variant_new ("(sh@a{sv})", "", 0, portal_options (0)), fds,
    G_DBUS_MESSAGE_BYTE_ORDER_LITTLE_ENDIAN);
  g_assert_cmpstr (g_dbus_message_get_error_name (file), ==, "org.freedesktop.DBus.Error.AccessDenied");
  g_autoptr(GDBusMessage) uri = portal_call (f, CAN_TALK_NAME, PORTAL_IFACE, "OpenURI",
    g_variant_new ("(ss@a{sv})", "", "tg://resolve?domain=fixture", portal_options (0)), NULL,
    G_DBUS_MESSAGE_BYTE_ORDER_LITTLE_ENDIAN);
  g_assert_cmpstr (g_dbus_message_get_error_name (uri), ==, "org.freedesktop.DBus.Error.AccessDenied");
}

static void
test_portal_cli (Fixture *f, gconstpointer context G_GNUC_UNUSED)
{
  const char *invalid[] = { "HTTP", "https,", ",https", "https,,file", "https:*", "file://", "9https" };
  alarm (30);
  for (guint i = 0; i < G_N_ELEMENTS (invalid) + 2; i++)
    {
      g_autoptr(GError) error = NULL;
      g_autofree char *option = g_strconcat ("--pwrap-portal-open=",
        i < G_N_ELEMENTS (invalid) ? invalid[i] : "https", NULL);
      g_autoptr(GSubprocess) process = g_subprocess_new (G_SUBPROCESS_FLAGS_STDERR_PIPE, &error,
        f->proxy_path, f->dbus_address, f->proxy_socket,
        i == G_N_ELEMENTS (invalid) ? "--log" : "--filter", option,
        i == G_N_ELEMENTS (invalid) + 1 ? "--pwrap-portal-open=file" : "--log", NULL);
      g_assert_no_error (error);
      g_assert_true (g_subprocess_wait (process, NULL, &error));
      g_assert_no_error (error);
      g_assert_false (g_subprocess_get_successful (process));
    }
}

typedef struct {
  const char *uri;
  const char *path;
  guint response;
  int calls;
  gboolean file_ask, uri_ask;
} PortalFixture;

/* Only the two standard methods and Request.Response. libportal, not pwrap,
 * implements the request protocol and turns a local path into a read-only FD. */
static GDBusMessage *
portal_respond (GDBusConnection *connection, GDBusMessage *message,
                gboolean incoming, gpointer data)
{
  PortalFixture *fixture = data;
  g_autoptr(GVariant) options = NULL;
  g_autoptr(GDBusMessage) reply = NULL;
  g_autoptr(GDBusMessage) response = NULL;
  g_autoptr(GError) error = NULL;
  g_autofree char *sender_component = NULL;
  g_autofree char *request = NULL;
  const char *parent, *token;
  gboolean ask;
  if (!incoming || g_dbus_message_get_message_type (message) != G_DBUS_MESSAGE_TYPE_METHOD_CALL ||
      g_strcmp0 (g_dbus_message_get_path (message), PORTAL_PATH) != 0)
    return message;
  g_assert_cmpstr (g_dbus_message_get_interface (message), ==, PORTAL_IFACE);
  if (fixture->path != NULL)
    {
      int index, fd;
      struct stat expected, observed;
      g_assert_cmpstr (g_dbus_message_get_member (message), ==, "OpenFile");
      g_variant_get (g_dbus_message_get_body (message), "(&sh@a{sv})", &parent, &index, &options);
      fd = g_unix_fd_list_get (g_dbus_message_get_unix_fd_list (message), index, &error);
      g_assert_no_error (error);
      g_assert_cmpint (fcntl (fd, F_GETFL) & O_ACCMODE, ==, O_RDONLY);
      g_assert_cmpint (stat (fixture->path, &expected), ==, 0);
      g_assert_cmpint (fstat (fd, &observed), ==, 0);
      g_assert_cmpuint (observed.st_ino, ==, expected.st_ino);
      g_assert_cmpuint (observed.st_dev, ==, expected.st_dev);
      close (fd);
    }
  else
    {
      const char *uri;
      g_assert_cmpstr (g_dbus_message_get_member (message), ==, "OpenURI");
      g_variant_get (g_dbus_message_get_body (message), "(&s&s@a{sv})", &parent, &uri, &options);
      g_assert_cmpstr (uri, ==, fixture->uri);
      g_assert_cmpuint (g_dbus_message_get_num_unix_fds (message), ==, 0);
    }
  g_assert_true (g_variant_lookup (options, "ask", "b", &ask));
  g_assert_cmpint (ask, ==, fixture->path != NULL ? fixture->file_ask : fixture->uri_ask);
  g_assert_true (g_variant_lookup (options, "handle_token", "&s", &token));
  sender_component = g_strdup (g_dbus_message_get_sender (message) + 1);
  g_strdelimit (sender_component, ".", '_');
  request = g_strdup_printf ("/org/freedesktop/portal/desktop/request/%s/%s", sender_component, token);
  reply = g_dbus_message_new_method_reply (message);
  g_dbus_message_set_body (reply, g_variant_new ("(o)", request));
  g_dbus_connection_send_message (connection, reply, G_DBUS_SEND_MESSAGE_FLAGS_NONE, NULL, &error);
  g_assert_no_error (error);
  response = g_dbus_message_new_signal (request, "org.freedesktop.portal.Request", "Response");
  g_dbus_message_set_destination (response, g_dbus_message_get_sender (message));
  g_dbus_message_set_body (response, g_variant_new ("(ua{sv})", fixture->response, NULL));
  g_atomic_int_inc (&fixture->calls);
  g_dbus_connection_send_message (connection, response, G_DBUS_SEND_MESSAGE_FLAGS_NONE, NULL, &error);
  g_assert_no_error (error);
  g_object_unref (message);
  return NULL;
}

static void
test_portal_client (Fixture *f, gconstpointer context G_GNUC_UNUSED)
{
  PortalFixture fixture = { .file_ask = g_strcmp0 (f->portal_file_ask, "--pwrap-portal-file-ask=false") != 0,
                             .uri_ask = g_strcmp0 (f->portal_uri_ask, "--pwrap-portal-uri-ask=true") == 0 };
  g_autoptr(GError) error = NULL;
  g_autofree char *path = g_build_filename (f->temp_directory, "тестовый файл.txt", NULL);
  g_autofree char *missing = g_build_filename (f->temp_directory, "missing-fixture", NULL);
  g_autofree char *uri = g_filename_to_uri (path, NULL, &error);
  g_autoptr(GSubprocessLauncher) launcher = g_subprocess_launcher_new (G_SUBPROCESS_FLAGS_STDERR_PIPE);
  const char *inputs[] = { "https://example.invalid/fixture", path, uri, uri, uri, missing };
  guint filter = g_dbus_connection_add_filter (f->can_talk_conn.conn, portal_respond, &fixture, NULL);
  int calls = 0;
  alarm (30);
  g_assert_no_error (error);
  g_file_set_contents (path, "owned fixture", -1, &error);
  g_assert_no_error (error);
  fixture_start_proxy (f);
  g_subprocess_launcher_setenv (launcher, "DBUS_SESSION_BUS_ADDRESS", f->proxy_address, TRUE);
  g_subprocess_launcher_setenv (launcher, "LIBPORTAL_PORTAL_BUS_NAME", CAN_TALK_NAME, TRUE);
  for (guint i = 0; i < G_N_ELEMENTS (inputs); i++)
    {
      fixture.uri = i == 0 ? inputs[i] : NULL;
      fixture.path = i == 0 ? NULL : path;
      fixture.response = i < 3 ? 0 : i - 2;
      g_autoptr(GSubprocess) process = g_subprocess_launcher_spawn (launcher, &error,
        g_getenv ("PWRAP_PORTAL_CLIENT"), inputs[i], NULL);
      g_assert_no_error (error);
      g_assert_true (g_subprocess_wait (process, NULL, &error));
      g_assert_no_error (error);
      g_assert_cmpint (g_subprocess_get_successful (process), ==, i < 3);
      if (i != 5) calls++;
      g_assert_cmpint (g_atomic_int_get (&fixture.calls), ==, calls);
    }
  g_dbus_connection_remove_filter (f->can_talk_conn.conn, filter);
  g_unlink (path);
}

static void
test_portal_ask_cli (Fixture *f, gconstpointer context G_GNUC_UNUSED)
{
  const char *cases[][3] = {
    { PORTAL_POLICY, "--pwrap-portal-file-ask=0", "--log" },
    { PORTAL_POLICY, "--pwrap-portal-uri-ask=FALSE", "--log" },
    { PORTAL_POLICY, "--pwrap-portal-file-ask=", "--log" },
    { PORTAL_POLICY, "--pwrap-portal-uri-ask=true ", "--log" },
    { PORTAL_POLICY, "--pwrap-portal-file-ask=true", "--pwrap-portal-file-ask=false" },
    { PORTAL_POLICY, "--pwrap-portal-uri-ask=false", "--pwrap-portal-uri-ask=false" },
    { "--log", "--pwrap-portal-file-ask=false", "--log" },
    { "--log", "--pwrap-portal-uri-ask=true", "--log" }
  };
  alarm (30);
  for (guint i = 0; i < G_N_ELEMENTS (cases); i++)
    {
      g_autoptr(GError) error = NULL;
      g_autoptr(GSubprocess) process = g_subprocess_new (G_SUBPROCESS_FLAGS_STDERR_PIPE, &error,
        f->proxy_path, f->dbus_address, f->proxy_socket, "--filter",
        cases[i][0], cases[i][1], cases[i][2], NULL);
      g_assert_no_error (error);
      g_assert_true (g_subprocess_wait (process, NULL, &error));
      g_assert_no_error (error);
      g_assert_false (g_subprocess_get_successful (process));
    }
}

static void
pwrap_portal_add_tests (void)
{
  g_test_add ("/pwrap/portal/file", Fixture, default_policy, setup, test_portal_file, teardown);
  g_test_add ("/pwrap/portal/uri", Fixture, default_policy, setup, test_portal_uri, teardown);
  g_test_add ("/pwrap/portal/denied", Fixture, default_policy, setup, test_portal_denied, teardown);
  g_test_add ("/pwrap/portal/restricted", Fixture, restricted_policy, setup, test_portal_restricted, teardown);
  g_test_add ("/pwrap/portal/empty", Fixture, empty_policy, setup, test_portal_restricted, teardown);
  g_test_add ("/pwrap/portal/cli", Fixture, NULL, setup, test_portal_cli, teardown);
  g_test_add ("/pwrap/portal/client", Fixture, default_policy, setup, test_portal_client, teardown);
  g_test_add ("/pwrap/portal/file-auto", Fixture, opposite_policy, setup, test_portal_file, teardown);
  g_test_add ("/pwrap/portal/uri-ask", Fixture, opposite_policy, setup, test_portal_uri, teardown);
  g_test_add ("/pwrap/portal/client-opposite", Fixture, opposite_policy, setup, test_portal_client, teardown);
  g_test_add ("/pwrap/portal/ask-cli", Fixture, NULL, setup, test_portal_ask_cli, teardown);
}
