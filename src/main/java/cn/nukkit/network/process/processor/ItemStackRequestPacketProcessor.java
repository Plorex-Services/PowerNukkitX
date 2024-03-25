package cn.nukkit.network.process.processor;

import cn.nukkit.Player;
import cn.nukkit.PlayerHandle;
import cn.nukkit.Server;
import cn.nukkit.event.inventory.ItemStackRequestActionEvent;
import cn.nukkit.inventory.Inventory;
import cn.nukkit.inventory.fake.FakeInventory;
import cn.nukkit.inventory.request.*;
import cn.nukkit.network.process.DataPacketProcessor;
import cn.nukkit.network.protocol.ItemStackRequestPacket;
import cn.nukkit.network.protocol.ItemStackResponsePacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.network.protocol.types.itemstack.ContainerSlotType;
import cn.nukkit.network.protocol.types.itemstack.request.ItemStackRequest;
import cn.nukkit.network.protocol.types.itemstack.request.action.ItemStackRequestAction;
import cn.nukkit.network.protocol.types.itemstack.request.action.ItemStackRequestActionType;
import cn.nukkit.network.protocol.types.itemstack.response.ItemStackResponse;
import cn.nukkit.network.protocol.types.itemstack.response.ItemStackResponseContainer;
import cn.nukkit.network.protocol.types.itemstack.response.ItemStackResponseStatus;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ItemStackRequestPacketProcessor extends DataPacketProcessor<ItemStackRequestPacket> {
    static final EnumMap<ItemStackRequestActionType, ItemStackRequestActionProcessor<?>> PROCESSORS = new EnumMap<>(ItemStackRequestActionType.class);

    static {
        PROCESSORS.put(ItemStackRequestActionType.CONSUME, new ConsumeActionProcessor());
        PROCESSORS.put(ItemStackRequestActionType.CRAFT_CREATIVE, new CraftCreativeActionProcessor());
        PROCESSORS.put(ItemStackRequestActionType.CRAFT_RECIPE, new CraftRecipeActionProcessor());
        PROCESSORS.put(ItemStackRequestActionType.CRAFT_RESULTS_DEPRECATED, new CraftResultDeprecatedActionProcessor());
        PROCESSORS.put(ItemStackRequestActionType.CREATE, new CreateActionProcessor());
        PROCESSORS.put(ItemStackRequestActionType.DESTROY, new DestroyActionProcessor());
        PROCESSORS.put(ItemStackRequestActionType.DROP, new DropActionProcessor());
        PROCESSORS.put(ItemStackRequestActionType.PLACE, new PlaceActionProcessor());
        PROCESSORS.put(ItemStackRequestActionType.SWAP, new SwapActionProcessor());
        PROCESSORS.put(ItemStackRequestActionType.TAKE, new TakeActionProcessor());
        PROCESSORS.put(ItemStackRequestActionType.CRAFT_RECIPE_OPTIONAL, new CraftRecipeOptionalProcessor());
        PROCESSORS.put(ItemStackRequestActionType.CRAFT_REPAIR_AND_DISENCHANT, new CraftGrindstoneActionProcessor());
        PROCESSORS.put(ItemStackRequestActionType.MINE_BLOCK, new MineBlockActionProcessor());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(@NotNull PlayerHandle playerHandle, @NotNull ItemStackRequestPacket pk) {
        Player player = playerHandle.player;
        List<ItemStackResponse> responses = new ArrayList<>();
        for (ItemStackRequest request : pk.getRequests()) {
            ItemStackResponse itemStackResponse = new ItemStackResponse(ItemStackResponseStatus.OK, request.getRequestId(), new ArrayList<>());
            Map<ContainerSlotType, ItemStackResponseContainer> responseContainerMap = new LinkedHashMap<>();
            ItemStackRequestContext itemStackRequestContext = new ItemStackRequestContext(request);

            int index = 0;
            for (ItemStackRequestAction itemStackRequestAction : request.getActions()) {
                itemStackRequestContext.setCurrentActionIndex(index++);

                ItemStackRequestActionProcessor<ItemStackRequestAction> processor = (ItemStackRequestActionProcessor<ItemStackRequestAction>) PROCESSORS.get(itemStackRequestAction.getType());
                if (processor == null) {
                    log.warn("Unhandled inventory itemStackRequestAction type " + itemStackRequestAction.getType());

                    continue;
                }

                ItemStackRequestActionEvent event = new ItemStackRequestActionEvent(player, itemStackRequestAction, itemStackRequestContext);
                Server.getInstance().getPluginManager().callEvent(event);

                Inventory topWindow = player.getTopWindow().orElse(null);
                if (topWindow instanceof FakeInventory) {
                    ((FakeInventory) topWindow).handle(event);
                }

                ActionResponse response = event.isCancelled() ? itemStackRequestContext.error() : event.getResponse();
                if (response == null) {
                    response = processor.handle(itemStackRequestAction, player, itemStackRequestContext);
                }

                if (response == null) continue;

                if (!response.ok()) {
                    itemStackResponse.setResult(ItemStackResponseStatus.ERROR);
                    itemStackResponse.getContainers().clear();

                    responses.add(itemStackResponse);

                    break;
                }

                for (ItemStackResponseContainer container : response.containers()) {
                    ItemStackResponseContainer oldContainer = responseContainerMap.get(container.getContainer());
                    if (oldContainer == null) {
                        responseContainerMap.put(container.getContainer(), container);
                    } else {
                        oldContainer.getItems().addAll(container.getItems());
                    }
                }
            }

            itemStackResponse.getContainers().addAll(responseContainerMap.values());
            responses.add(itemStackResponse);
        }

        var itemStackResponsePacket = new ItemStackResponsePacket();
        itemStackResponsePacket.entries.addAll(responses);
        player.dataPacket(itemStackResponsePacket);
    }

    @Override
    public int getPacketId() {
        return ProtocolInfo.ITEM_STACK_REQUEST_PACKET;
    }
}
