/* CLI glue only: libportal owns file-FD transfer and asynchronous requests.
 * The trusted D-Bus proxy enforces policy, even if this client is bypassed. */
#include <libportal/portal.h>
#include <libportal/openuri.h>

static int status = 1;

static void
opened (GObject *portal, GAsyncResult *result, gpointer loop)
{
  g_autoptr(GError) error = NULL;
  if (xdp_portal_open_uri_finish (XDP_PORTAL (portal), result, &error))
    status = 0;
  else if (!g_error_matches (error, G_IO_ERROR, G_IO_ERROR_CANCELLED))
    g_printerr ("xdg-open: %s\n", error->message);
  g_main_loop_quit (loop);
}

int
main (int argc, char **argv)
{
  g_autoptr(GError) error = NULL;
  g_autoptr(XdpPortal) portal = NULL;
  g_autoptr(GFile) file = NULL;
  g_autofree char *uri = NULL;
  g_autoptr(GMainLoop) loop = NULL;
  if (argc != 2)
    {
      g_printerr ("usage: xdg-open URI-or-file\n");
      return 2;
    }
  portal = xdp_portal_initable_new (&error);
  if (portal == NULL)
    {
      g_printerr ("xdg-open: %s\n", error->message);
      return 1;
    }
  file = g_file_new_for_commandline_arg (argv[1]);
  uri = g_file_get_uri (file);
  loop = g_main_loop_new (NULL, FALSE);
  xdp_portal_open_uri (portal, NULL, uri,
    g_file_is_native (file) ? XDP_OPEN_URI_FLAG_ASK : XDP_OPEN_URI_FLAG_NONE,
    NULL, opened, loop);
  g_main_loop_run (loop);
  return status;
}
