package com.frederikberg.mailverdict.kit.signins

import com.frederikberg.mailverdict.contract.SignInKind
import com.frederikberg.mailverdict.contract.SignInResponse

/** Whether the sign-in has been signed out already. */
val SignInResponse.isRevoked: Boolean get() = revokedAt != null

/** What a sign-in is called and what signing it out does, in the words the web uses. */
object SignInDisplay {
    /**
     * A phone or client by the name it sends, a person's browsers together, a machine client by its
     * subject when it sends no name.
     */
    fun label(signIn: SignInResponse): String {
        val name = signIn.name?.takeIf { it.isNotBlank() }
        return when {
            signIn.kind == SignInKind.Browser -> "Web browsers"
            name != null -> name
            signIn.kind == SignInKind.Phone -> "Phone"
            else -> signIn.subject
        }
    }

    /** What revoking it does, said to whoever is about to. */
    fun consequence(signIn: SignInResponse): String {
        val base =
            when (signIn.kind) {
                SignInKind.Browser ->
                    "Every web browser of this person is signed out. Signing in again at the identity provider, " +
                        "with a password or passkey, lets them back in."
                SignInKind.Phone -> "This phone is signed out. Signing in again on it starts a new sign-in."
                else ->
                    "This access key stops working for good. A replacement is a new machine user at the " +
                        "identity provider."
            }
        return if (signIn.current) "$base That includes this one, right away." else base
    }
}
