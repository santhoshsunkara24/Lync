package app.wallhop

import app.wallhop.otp.OtpExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpExtractorTest {

    private fun assertCode(expected: String, message: String) =
        assertEquals(message, expected, OtpExtractor.extract(message))

    private fun assertNoCode(message: String) =
        assertNull(message, OtpExtractor.extract(message))

    @Test fun indianBank() = assertCode(
        "482913",
        "482913 is the OTP for your transaction of Rs. 2,499.00 at AMAZON on HDFC Bank card ending 4321. Valid for 10 mins. Do not share.",
    )

    @Test fun amountBeforeCode() = assertCode(
        "839201",
        "Rs 1500 debited. OTP to confirm is 839201. Never share your OTP.",
    )

    @Test fun upi() = assertCode(
        "5517",
        "Dear Customer, 5517 is your OTP for UPI PIN set. Valid for 5 minutes. -SBI",
    )

    @Test fun googlePrefix() = assertCode("123456", "G-123456 is your Google verification code.")

    @Test fun whatsapp() = assertCode(
        "382915",
        "Your WhatsApp code: 382-915. Don't share this code with others.",
    )

    @Test fun spacedCode() = assertCode("123456", "Your Uber code is 123 456. Reply STOP to unsubscribe.")

    @Test fun accountNumberIgnored() = assertCode(
        "773302",
        "OTP 773302 for login to A/c XX9876. Valid till 10:45.",
    )

    @Test fun dateNotCode() = assertCode(
        "61025",
        "Use 61025 as your verification code. Expires 12/05/2026.",
    )

    @Test fun spanish() = assertCode("904421", "Tu código de verificación es 904421")

    @Test fun portuguese() = assertCode("3391", "Seu código de verificação do Nubank é 3391.")

    @Test fun german() = assertCode("552019", "Ihr Bestätigungscode lautet 552019.")

    @Test fun chinese() = assertCode("664120", "【淘宝】验证码664120，用于身份验证，5分钟内有效。")

    @Test fun hindi() = assertCode("448812", "आपका ओटीपी 448812 है। इसे किसी के साथ साझा न करें।")

    @Test fun alphanumeric() = assertCode("A7K29Q", "Your sign-in code is A7K29Q")

    @Test fun eightDigits() = assertCode("12345678", "Your Microsoft security code is 12345678")

    @Test fun noKeyword() = assertNoCode("Your order 123456 has shipped and will arrive Friday.")

    @Test fun deliveryUpdate() = assertNoCode("Hey, meet at 1930 near the station?")

    @Test fun onlyAmount() = assertNoCode("Rs 4500 credited to your account. Avl bal Rs 23000.")

    @Test fun phoneNumberIgnored() = assertNoCode("Call 9876543210 to verify your address.")

    @Test fun shoppingDoesNotMatchPin() = assertNoCode("Shopping list: 2 eggs, 1200 grams flour")

    // A word ending in "x" or "box" right before the code isn't an account marker.
    @Test fun brandEndingInX() = assertCode("482913", "Roblox: 482913 is your code")

    @Test fun dropbox() = assertCode("553311", "Your Dropbox code is 553311")

    @Test fun androidWord() = assertCode("120934", "Android verification code 120934")

    @Test fun groupedPhoneNumberIgnored() = assertNoCode("Call 1-888-280-4331 to confirm your order")

    @Test fun codeBeforePhoneNumber() = assertCode("482913", "Your code is 482913. Call 1-800-555-0199 if this wasn't you")

    @Test fun indianPhoneNumberIgnored() = assertNoCode("To verify, call us on +91 98765 43210")
}
