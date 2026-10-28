# Flatpak build of the Linux viewer

It builds offline from the release tag, with the same minimal FFmpeg
(VAAPI + NVDEC) as the portable build.

Build and install for your user:

    flatpak-builder --user --install --force-clean build-dir io.github.monkadev.SwitchRemoteFrame.yaml

Or make a single-file bundle:

    flatpak-builder --repo=repo --force-clean build-dir io.github.monkadev.SwitchRemoteFrame.yaml
    flatpak build-bundle repo switch-remote-frame.flatpak io.github.monkadev.SwitchRemoteFrame
    flatpak install --user switch-remote-frame.flatpak

USB needs the udev rule on the host (a Flatpak cannot install it), once:

    sudo cp ../tools/raw-recv/99-switch-remote-frame.rules /etc/udev/rules.d/
    sudo udevadm control --reload-rules && sudo udevadm trigger

Known limitation: plug the Switch in after the viewer has started, otherwise
restart the viewer.

On a new release: update the tag and its sha256 in the yaml, and add a
`<release>` to the metainfo.
