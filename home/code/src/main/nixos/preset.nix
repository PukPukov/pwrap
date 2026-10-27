# One opt-in/underlying-service gate for all native preset integrations.
{ config, lib }:
name: { options ? {}, service ? name, settings }:
{
  options.security.pwrap.presets.${name} = {
    apply = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = if service == null then
        "Apply native confinement to explicitly selected ${name} pools. This does not enable the service."
      else
        "Apply native ${name} service confinement. This does not set services.${service}.enable.";
    };
  } // options;
  config = lib.mkIf config.security.pwrap.presets.${name}.apply (lib.mkMerge [
    { assertions = lib.optional (service != null) {
      assertion = config.services.${service}.enable;
      message = "security.pwrap.presets.${name} requires services.${service}.enable = true; it never enables the service itself.";
    }; }
    settings
  ]);
}
