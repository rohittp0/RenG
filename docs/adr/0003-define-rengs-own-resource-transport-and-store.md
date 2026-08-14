# Define RenG's own resource transport and store

RenG declares its own `ResourceTransport` and `RawResourceStore` interfaces, in RenG's own vocabulary,
and adapts them onto Rentile's equivalents internally. Rentile is an `implementation` dependency, so no
Rentile type appears anywhere in RenG's public API and Rentile's ABI is not RenG's ABI. A consumer
implements one pair of RenG interfaces covering everything RenG needs — the basemap resources it
proxies down to Rentile, and the sticker images, GLB meshes, and model textures it acquires itself.

Reusing Rentile's interfaces directly was rejected because Rentile's resource identity is a closed
enum. `ResourceClass` admits exactly `STYLE`, `TILE_JSON`, `VECTOR_TILE`, `RASTER_TILE`, `DEM_TILE`,
`SPRITE_JSON`, `SPRITE_IMAGE`, and `GEO_JSON`, and both `TransportRequest` and `RawResourceKey` are
typed on it. A GLB mesh, a sticker image, and a model texture are none of those, so every RenG-owned
fetch would have to travel mislabelled as something it is not, and a consumer's persistent cache would
key RenG's meshes under Rentile's vocabulary. Owning the boundary also keeps a Rentile patch release
from moving RenG's reviewed public ABI.

The cost is that a consumer already wired to Rentile writes a second, small delegating adapter rather
than handing the same object to both libraries. Shipping a public RenG-to-Rentile adapter would remove
that cost but would put Rentile's types back into RenG's public API, which is the thing this decision
exists to prevent.
