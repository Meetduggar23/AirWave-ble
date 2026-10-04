package com.airwave.app

import com.journeyapps.barcodescanner.CaptureActivity

/** v3.2.6: zxing CaptureActivity locked to portrait. Declared in the manifest,
 *  so IntentIntegrator's default landscape-oriented CaptureActivity is replaced. */
class PortraitScannerActivity : CaptureActivity()
