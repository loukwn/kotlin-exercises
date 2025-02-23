package coroutines.comment.commentservice

import domain.comment.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class CommentService(
    private val commentRepository: CommentRepository,
    private val userService: UserService,
    private val commentFactory: CommentFactory
) {
    suspend fun addComment(
        token: String,
        collectionKey: String,
        body: AddComment
    ) {
        val userId = userService.readUserId(token)
        val commentDocument = commentFactory.toCommentDocument(
            userId = userId,
            collectionKey = collectionKey,
            body = body,
        )
        commentRepository.addComment(commentDocument)
    }

    suspend fun getComments(
        collectionKey: String
    ): CommentsCollection {
        // (Naive: Calling findUserById on every comment)
//        return coroutineScope {
//            val commentElements = commentRepository.getComments(collectionKey)
//                .map { async { userService.findUserById(it.userId) to it } }
//                .awaitAll()
//                .map { (user, comment) ->
//                    CommentElement(
//                        id = comment._id,
//                        collectionKey = collectionKey,
//                        user = user,
//                        comment = comment.comment,
//                        date = comment.date
//                    )
//                }
//            CommentsCollection(
//                collectionKey = collectionKey,
//                elements = commentElements,
//            )
//        }
        return coroutineScope {
            val comments = commentRepository.getComments(collectionKey)
            val uniqueUsers = comments
                .map { it.userId }
                .toSet()
                .map { userId -> async { userId to userService.findUserById(userId) } }
                .awaitAll()
                .associate { it }

            val commentElements = comments.map { comment ->
                CommentElement(
                    id = comment._id,
                    collectionKey = collectionKey,
                    user = uniqueUsers[comment.userId],
                    comment = comment.comment,
                    date = comment.date
                )
            }

            CommentsCollection(
                collectionKey = collectionKey,
                elements = commentElements
            )
        }

    }
}
