package com.difft.android.migration.model

import com.difft.android.p2p.engine.io.TransferCommittedUnconfirmed
import com.difft.android.p2p.engine.io.TransferStorageFault







sealed class MigrationException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    
    class SnapshotFailed(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    
    class EncryptionFailed(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    








    class HmacMismatch(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    
    class DecryptionFailed(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    



    class StreamInterrupted(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    



    class SinkInterrupted(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    
    class RestoreFailed(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    
    class SignInFailed(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    







    class RestoreAbortedCleanState(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    








    class OutOfSpace(message: String, cause: Throwable? = null) :
        MigrationException(message, cause), TransferStorageFault

    





    class CommittedUnconfirmed(message: String, cause: Throwable? = null) :
        MigrationException(message, cause), TransferCommittedUnconfirmed
}
