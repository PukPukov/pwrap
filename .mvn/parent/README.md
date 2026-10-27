# Hermetic build input

Unmodified `puklapom` 1.4.10 POM from the requested local repository
`/home/база данных/проекты/lib/puklapom/`, commit
`e6de1baa1d0753239fb35a01b4847ccac06d2ec8`.

Development Maven builds still use the sibling repository via `relativePath`.
Nix substitutes that path with this exact snapshot, because the private/unpushed
parent is not available to a network-isolated builder. This is a parent POM,
not another pwrap Maven module. Update it only together with the parent version.
