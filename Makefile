LOGO := media/logo/logo.png
RES  := app/src/main/res

LEGACY_ICONS := \
	$(RES)/mipmap-mdpi/ic_launcher.webp \
	$(RES)/mipmap-hdpi/ic_launcher.webp \
	$(RES)/mipmap-xhdpi/ic_launcher.webp \
	$(RES)/mipmap-xxhdpi/ic_launcher.webp \
	$(RES)/mipmap-xxxhdpi/ic_launcher.webp

ADAPTIVE_ICONS := \
	$(RES)/mipmap-mdpi/ic_launcher_foreground.png \
	$(RES)/mipmap-hdpi/ic_launcher_foreground.png \
	$(RES)/mipmap-xhdpi/ic_launcher_foreground.png \
	$(RES)/mipmap-xxhdpi/ic_launcher_foreground.png \
	$(RES)/mipmap-xxxhdpi/ic_launcher_foreground.png

TV_BANNER := $(RES)/drawable-xhdpi/tv_banner.png

.PHONY: all icons clean

all: icons $(TV_BANNER)

icons: $(LEGACY_ICONS) $(ADAPTIVE_ICONS)

# Legacy flat launcher icons (white background for pre-API-26 launchers)
$(RES)/mipmap-mdpi/ic_launcher.webp:    $(LOGO) ; magick $< -resize  48x48  -background white -flatten $@
$(RES)/mipmap-hdpi/ic_launcher.webp:    $(LOGO) ; magick $< -resize  72x72  -background white -flatten $@
$(RES)/mipmap-xhdpi/ic_launcher.webp:   $(LOGO) ; magick $< -resize  96x96  -background white -flatten $@
$(RES)/mipmap-xxhdpi/ic_launcher.webp:  $(LOGO) ; magick $< -resize 144x144 -background white -flatten $@
$(RES)/mipmap-xxxhdpi/ic_launcher.webp: $(LOGO) ; magick $< -resize 192x192 -background white -flatten $@

# Adaptive icon foreground layers (108dp canvas, logo fits 72dp safe zone)
$(RES)/mipmap-mdpi/ic_launcher_foreground.png:    $(LOGO) ; magick $< -resize  72x72  -gravity center -background none -extent  108x108 $@
$(RES)/mipmap-hdpi/ic_launcher_foreground.png:    $(LOGO) ; magick $< -resize 108x108 -gravity center -background none -extent  162x162 $@
$(RES)/mipmap-xhdpi/ic_launcher_foreground.png:   $(LOGO) ; magick $< -resize 144x144 -gravity center -background none -extent  216x216 $@
$(RES)/mipmap-xxhdpi/ic_launcher_foreground.png:  $(LOGO) ; magick $< -resize 216x216 -gravity center -background none -extent  324x324 $@
$(RES)/mipmap-xxxhdpi/ic_launcher_foreground.png: $(LOGO) ; magick $< -resize 288x288 -gravity center -background none -extent  432x432 $@

# TV banner: 320×180dp at xhdpi = 640×360px
$(TV_BANNER): $(LOGO)
	mkdir -p $(dir $@)
	magick $< -resize x300 -gravity center -background white -extent 640x360 $@

clean:
	rm -f $(LEGACY_ICONS) $(ADAPTIVE_ICONS) $(TV_BANNER)
