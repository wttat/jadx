package jadx.core.dex.visitors.blocksmaker;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.JumpInfo;
import jadx.core.dex.instructions.IfNode;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.TargetInsnNode;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.trycatch.CatchAttr;
import jadx.core.dex.trycatch.ExcHandlerAttr;
import jadx.core.dex.trycatch.ExceptionHandler;
import jadx.core.dex.trycatch.SplitterBlockAttr;
import jadx.core.dex.visitors.AbstractVisitor;
import jadx.core.utils.BlockUtils;
import jadx.core.utils.exceptions.JadxRuntimeException;

public class BlockSplitter extends AbstractVisitor {

	// leave these instructions alone in block node
	private static final Set<InsnType> SEPARATE_INSNS = EnumSet.of(
			InsnType.RETURN,
			InsnType.IF,
			InsnType.SWITCH,
			InsnType.MONITOR_ENTER,
			InsnType.MONITOR_EXIT,
			InsnType.THROW
	);

	@Override
	public void visit(MethodNode mth) {
		if (mth.isNoCode()) {
			return;
		}
		mth.checkInstructions();

		mth.initBasicBlocks();
		splitBasicBlocks(mth);
		removeInsns(mth);
		removeEmptyDetachedBlocks(mth);
		initBlocksInTargetNodes(mth);
	}

	/**
	 * Init 'then' and 'else' blocks for 'if' instruction.
	 */
	private static void initBlocksInTargetNodes(MethodNode mth) {
		mth.getBasicBlocks().forEach(block -> {
			InsnNode lastInsn = BlockUtils.getLastInsn(block);
			if (lastInsn instanceof TargetInsnNode) {
				((TargetInsnNode) lastInsn).initBlocks(block);
			}
		});
	}

	private static void splitBasicBlocks(MethodNode mth) {
		InsnNode prevInsn = null;
		Map<Integer, BlockNode> blocksMap = new HashMap<>();
		BlockNode curBlock = startNewBlock(mth, 0);
		mth.setEnterBlock(curBlock);

		// split into blocks
		for (InsnNode insn : mth.getInstructions()) {
			if (insn == null) {
				continue;
			}
			boolean startNew = false;
			if (prevInsn != null) {
				InsnType type = prevInsn.getType();
				if (type == InsnType.GOTO
						|| type == InsnType.THROW
						|| SEPARATE_INSNS.contains(type)) {

					if (type == InsnType.RETURN || type == InsnType.THROW) {
						mth.addExitBlock(curBlock);
					}
					BlockNode newBlock = startNewBlock(mth, insn.getOffset());
					if (type == InsnType.MONITOR_ENTER || type == InsnType.MONITOR_EXIT) {
						connect(curBlock, newBlock);
					}
					curBlock = newBlock;
					startNew = true;
				} else {
					startNew = isSplitByJump(prevInsn, insn)
							|| SEPARATE_INSNS.contains(insn.getType())
							|| isDoWhile(blocksMap, curBlock, insn)
							|| insn.contains(AType.EXC_HANDLER)
							|| prevInsn.contains(AFlag.TRY_LEAVE)
							|| prevInsn.getType() == InsnType.MOVE_EXCEPTION;
					if (startNew) {
						curBlock = connectNewBlock(mth, curBlock, insn.getOffset());
					}
				}
			}
			if (insn.contains(AFlag.TRY_ENTER)) {
				curBlock = insertSplitterBlock(mth, blocksMap, curBlock, insn, startNew);
			} else if (insn.contains(AType.EXC_HANDLER)) {
				processExceptionHandler(mth, curBlock, insn);
				blocksMap.put(insn.getOffset(), curBlock);
				curBlock.getInstructions().add(insn);
			} else {
				blocksMap.put(insn.getOffset(), curBlock);
				curBlock.getInstructions().add(insn);
			}
			prevInsn = insn;
		}
		// setup missing connections
		setupConnections(mth, blocksMap);
	}

	/**
	 * Make separate block for exception handler. New block already added if MOVE_EXCEPTION insn exists.
	 * Also link ExceptionHandler with current block.
	 */
	private static void processExceptionHandler(MethodNode mth, BlockNode curBlock, InsnNode insn) {
		ExcHandlerAttr excHandlerAttr = insn.get(AType.EXC_HANDLER);
		insn.remove(AType.EXC_HANDLER);

		BlockNode excHandlerBlock;
		if (insn.getType() == InsnType.MOVE_EXCEPTION) {
			excHandlerBlock = curBlock;
		} else {
			BlockNode newBlock = startNewBlock(mth, -1);
			newBlock.add(AFlag.SYNTHETIC);
			connect(newBlock, curBlock);

			excHandlerBlock = newBlock;
		}
		excHandlerBlock.addAttr(excHandlerAttr);
		excHandlerAttr.getHandler().setHandlerBlock(excHandlerBlock);
	}

	/**
	 * For try/catch make empty (splitter) block for connect handlers
	 */
	private static BlockNode insertSplitterBlock(MethodNode mth, Map<Integer, BlockNode> blocksMap,
	                                             BlockNode curBlock, InsnNode insn, boolean startNew) {
		BlockNode splitterBlock;
		if (insn.getOffset() == 0 || startNew) {
			splitterBlock = curBlock;
		} else {
			splitterBlock = connectNewBlock(mth, curBlock, insn.getOffset());
		}
		blocksMap.put(insn.getOffset(), splitterBlock);

		SplitterBlockAttr splitterAttr = new SplitterBlockAttr(splitterBlock);
		splitterBlock.add(AFlag.SYNTHETIC);
		splitterBlock.addAttr(splitterAttr);

		// add this insn in new block
		BlockNode newBlock = startNewBlock(mth, -1);
		newBlock.getInstructions().add(insn);
		newBlock.addAttr(splitterAttr);
		connect(splitterBlock, newBlock);
		return newBlock;
	}

	private static BlockNode connectNewBlock(MethodNode mth, BlockNode curBlock, int offset) {
		BlockNode block = startNewBlock(mth, offset);
		connect(curBlock, block);
		return block;
	}

	static BlockNode startNewBlock(MethodNode mth, int offset) {
		BlockNode block = new BlockNode(mth.getBasicBlocks().size(), offset);
		mth.getBasicBlocks().add(block);
		return block;
	}

	static void connect(BlockNode from, BlockNode to) {
		if (!from.getSuccessors().contains(to)) {
			from.getSuccessors().add(to);
		}
		if (!to.getPredecessors().contains(from)) {
			to.getPredecessors().add(from);
		}
	}

	static void removeConnection(BlockNode from, BlockNode to) {
		from.getSuccessors().remove(to);
		to.getPredecessors().remove(from);
	}

	static void replaceConnection(BlockNode source, BlockNode oldDest, BlockNode newDest) {
		removeConnection(source, oldDest);
		connect(source, newDest);
		replaceTarget(source, oldDest, newDest);
	}

	static BlockNode insertBlockBetween(MethodNode mth, BlockNode source, BlockNode target) {
		BlockNode newBlock = startNewBlock(mth, target.getStartOffset());
		newBlock.add(AFlag.SYNTHETIC);
		removeConnection(source, target);
		connect(source, newBlock);
		connect(newBlock, target);
		replaceTarget(source, target, newBlock);
		source.updateCleanSuccessors();
		newBlock.updateCleanSuccessors();
		return newBlock;
	}

	static void replaceTarget(BlockNode source, BlockNode oldTarget, BlockNode newTarget) {
		InsnNode lastInsn = BlockUtils.getLastInsn(source);
		if (lastInsn instanceof TargetInsnNode) {
			((TargetInsnNode) lastInsn).replaceTargetBlock(oldTarget, newTarget);
		}
	}

	private static void setupConnections(MethodNode mth, Map<Integer, BlockNode> blocksMap) {
		for (BlockNode block : mth.getBasicBlocks()) {
			for (InsnNode insn : block.getInstructions()) {
				List<JumpInfo> jumps = insn.getAll(AType.JUMP);
				for (JumpInfo jump : jumps) {
					BlockNode srcBlock = getBlock(jump.getSrc(), blocksMap);
					BlockNode thisBlock = getBlock(jump.getDest(), blocksMap);
					connect(srcBlock, thisBlock);
				}
				connectExceptionHandlers(block, insn);
			}
		}
	}

	private static void connectExceptionHandlers(BlockNode block, InsnNode insn) {
		CatchAttr catches = insn.get(AType.CATCH_BLOCK);
		SplitterBlockAttr spl = block.get(AType.SPLITTER_BLOCK);
		if (catches == null || spl == null) {
			return;
		}
		BlockNode splitterBlock = spl.getBlock();
		boolean tryEnd = insn.contains(AFlag.TRY_LEAVE);
		for (ExceptionHandler h : catches.getTryBlock().getHandlers()) {
			BlockNode handlerBlock = h.getHandlerBlock();
			// skip self loop in handler
			if (splitterBlock != handlerBlock) {
				if (!handlerBlock.contains(AType.SPLITTER_BLOCK)) {
					handlerBlock.addAttr(spl);
				}
				connect(splitterBlock, handlerBlock);
			}
			if (tryEnd) {
				connect(block, handlerBlock);
			}
		}
	}

	private static boolean isSplitByJump(InsnNode prevInsn, InsnNode currentInsn) {
		List<JumpInfo> pJumps = prevInsn.getAll(AType.JUMP);
		for (JumpInfo jump : pJumps) {
			if (jump.getSrc() == prevInsn.getOffset()) {
				return true;
			}
		}
		List<JumpInfo> cJumps = currentInsn.getAll(AType.JUMP);
		for (JumpInfo jump : cJumps) {
			if (jump.getDest() == currentInsn.getOffset()) {
				return true;
			}
		}
		return false;
	}

	private static boolean isDoWhile(Map<Integer, BlockNode> blocksMap, BlockNode curBlock, InsnNode insn) {
		// split 'do-while' block (last instruction: 'if', target this block)
		if (insn.getType() != InsnType.IF) {
			return false;
		}
		IfNode ifs = (IfNode) insn;
		BlockNode targetBlock = blocksMap.get(ifs.getTarget());
		return targetBlock == curBlock;
	}

	private static BlockNode getBlock(int offset, Map<Integer, BlockNode> blocksMap) {
		BlockNode block = blocksMap.get(offset);
		if (block == null) {
			throw new JadxRuntimeException("Missing block: " + offset);
		}
		return block;
	}

	private static void removeInsns(MethodNode mth) {
		for (BlockNode block : mth.getBasicBlocks()) {
			block.getInstructions().removeIf(insn -> {
				if (!insn.isAttrStorageEmpty()) {
					return false;
				}
				InsnType insnType = insn.getType();
				return insnType == InsnType.GOTO || insnType == InsnType.NOP;
			});
		}
	}

	static boolean removeEmptyDetachedBlocks(MethodNode mth) {
		return mth.getBasicBlocks().removeIf(block ->
				block.getInstructions().isEmpty()
						&& block.getPredecessors().isEmpty()
						&& block.getSuccessors().isEmpty()
		);
	}
}
