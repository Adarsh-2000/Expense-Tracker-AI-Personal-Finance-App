package com.ledgernoir.tracker.ui.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class FormFactorTest {
    @Test fun coverWhenShort() {
        // Flip 7 cover ≈ 361x390dp
        assertEquals(FormFactor.Cover, formFactorFor(widthDp = 361, heightDp = 390))
    }

    @Test fun phoneWhenTall() {
        // Flip 7 main ≈ 407x950dp
        assertEquals(FormFactor.Phone, formFactorFor(widthDp = 407, heightDp = 950))
    }

    @Test fun wideWhenBroad() {
        assertEquals(FormFactor.Wide, formFactorFor(widthDp = 720, heightDp = 1000))
    }

    @Test fun wideBeatsCoverAtLargeWidth() {
        assertEquals(FormFactor.Wide, formFactorFor(widthDp = 640, heightDp = 400))
    }

    @Test fun boundaryHeightIsPhone() {
        assertEquals(FormFactor.Phone, formFactorFor(widthDp = 400, heightDp = 480))
    }

    @Test fun widthBoundaryIsWide() {
        // exactly 600dp wide → Wide (inclusive lower bound)
        assertEquals(FormFactor.Wide, formFactorFor(widthDp = 600, heightDp = 950))
    }

    @Test fun justBelowWidthBoundaryIsPhone() {
        assertEquals(FormFactor.Phone, formFactorFor(widthDp = 599, heightDp = 950))
    }
}
