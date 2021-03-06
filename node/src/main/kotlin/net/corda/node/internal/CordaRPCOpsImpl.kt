package net.corda.node.internal

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.node.NodeInfo
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.NetworkMapCache
import net.corda.core.node.services.StateMachineTransactionMapping
import net.corda.core.node.services.Vault
import net.corda.core.toObservable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.node.services.messaging.*
import net.corda.node.services.startFlowPermission
import net.corda.node.services.statemachine.FlowStateMachineImpl
import net.corda.node.services.statemachine.StateMachineManager
import net.corda.node.utilities.databaseTransaction
import org.jetbrains.exposed.sql.Database
import rx.Observable

/**
 * Server side implementations of RPCs available to MQ based client tools. Execution takes place on the server
 * thread (i.e. serially). Arguments are serialised and deserialised automatically.
 */
class CordaRPCOpsImpl(
        val services: ServiceHub,
        val smm: StateMachineManager,
        val database: Database
) : CordaRPCOps {
    override val protocolVersion: Int get() = 0

    override fun networkMapUpdates(): Pair<List<NodeInfo>, Observable<NetworkMapCache.MapChange>> {
        return services.networkMapCache.track()
    }

    override fun vaultAndUpdates(): Pair<List<StateAndRef<ContractState>>, Observable<Vault.Update>> {
        return databaseTransaction(database) {
            val (vault, updates) = services.vaultService.track()
            Pair(vault.states.toList(), updates)
        }
    }

    override fun verifiedTransactions(): Pair<List<SignedTransaction>, Observable<SignedTransaction>> {
        return databaseTransaction(database) {
            services.storageService.validatedTransactions.track()
        }
    }

    override fun stateMachinesAndUpdates(): Pair<List<StateMachineInfo>, Observable<StateMachineUpdate>> {
        val (allStateMachines, changes) = smm.track()
        return Pair(
                allStateMachines.map { StateMachineInfo.fromFlowStateMachineImpl(it) },
                changes.map { StateMachineUpdate.fromStateMachineChange(it) }
        )
    }

    override fun stateMachineRecordedTransactionMapping(): Pair<List<StateMachineTransactionMapping>, Observable<StateMachineTransactionMapping>> {
        return databaseTransaction(database) {
            services.storageService.stateMachineRecordedTransactionMapping.track()
        }
    }

    override fun nodeIdentity(): NodeInfo {
        return services.myInfo
    }

    override fun addVaultTransactionNote(txnId: SecureHash, txnNote: String) {
        return databaseTransaction(database) {
            services.vaultService.addNoteToTransaction(txnId, txnNote)
        }
    }

    override fun getVaultTransactionNotes(txnId: SecureHash): Iterable<String> {
        return databaseTransaction(database) {
            services.vaultService.getTransactionNotes(txnId)
        }
    }

    // TODO: Check that this flow is annotated as being intended for RPC invocation
    override fun <T : Any> startFlowDynamic(logicType: Class<out FlowLogic<T>>, vararg args: Any?): FlowHandle<T> {
        requirePermission(startFlowPermission(logicType))
        val stateMachine = services.invokeFlowAsync(logicType, *args) as FlowStateMachineImpl<T>
        return FlowHandle(
                id = stateMachine.id,
                progress = stateMachine.logic.progressTracker?.changes ?: Observable.empty<ProgressTracker.Change>(),
                returnValue = stateMachine.resultFuture.toObservable()
        )
    }
}
