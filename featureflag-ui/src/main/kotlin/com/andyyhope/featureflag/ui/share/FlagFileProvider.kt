package com.andyyhope.featureflag.ui.share

import androidx.core.content.FileProvider

/**
 * Serves the QR sheet's PNG, so it can be copied and shared. featureflag-ui's manifest registers it with
 * the authority `${applicationId}.featureflag.files`, unexported, for `cacheDir/featureflag/` only. A
 * companion app needs to do nothing: the manifest merges.
 */
public class FlagFileProvider : FileProvider()
