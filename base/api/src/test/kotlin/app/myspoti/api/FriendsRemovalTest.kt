package app.myspoti.api

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The friend-activity feed went with the Friends screen (v24): under the accountless
 * backend it could only ever be an empty list, so the interface method and its model
 * are gone rather than stubbed.
 */
class FriendsRemovalTest {

    @Test fun `the users api has no friend activity`() {
        assertTrue(Users::class.java.methods.none { it.name == "friendActivity" })
    }

    @Test fun `the friend activity model is gone`() {
        assertThrows(ClassNotFoundException::class.java) { Class.forName("app.myspoti.api.FriendActivity") }
    }
}
