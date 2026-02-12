ALTER TABLE poi
  ADD COLUMN active TINYINT(1) NOT NULL DEFAULT 1,
  ADD COLUMN address VARCHAR(255) NULL,
  ADD COLUMN description TEXT NULL,
  ADD COLUMN image_urls TEXT NULL,
  ADD COLUMN popularity_score INT NOT NULL DEFAULT 0,
  ADD COLUMN rating_score DECIMAL(3, 2) NOT NULL DEFAULT 0.0,
  ADD COLUMN geohash VARCHAR(16) NULL;

CREATE INDEX idx_poi_active_lat_lng ON poi(active, lat, lng);
CREATE INDEX idx_poi_active_category_geohash ON poi(active, category, geohash);
