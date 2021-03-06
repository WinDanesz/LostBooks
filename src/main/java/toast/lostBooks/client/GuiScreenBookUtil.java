package toast.lostBooks.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import toast.lostBooks.BookHelper;
import toast.lostBooks.MessageCurrPage;
import toast.lostBooks._LostBooks;
import cpw.mods.fml.common.ObfuscationReflectionHelper;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class GuiScreenBookUtil extends GuiScreen {
    private static final Logger logger = LogManager.getLogger();
    public static final ResourceLocation bookGuiTextures = new ResourceLocation("textures/gui/book.png");
    public final boolean pauseGame;
    /** The player editing the book */
    public final EntityPlayer editingPlayer;
    public final ItemStack bookObj;
    /** Whether the book is signed or can still be edited */
    public final boolean bookIsUnsigned;
    public boolean hasUpdated;
    public boolean settingTitle;
    /** Update ticks since the gui was opened */
    public int updateCount;
    public final int bookImageWidth = 192;
    public final int bookImageHeight = 192;
    public int bookTotalPages = 1;
    public int currPage, lastCurrPage;
    public NBTTagList bookPages;
    public String bookTitle = "";
    public GuiScreenBookUtil.NextPageButton buttonNextPage;
    public GuiScreenBookUtil.NextPageButton buttonPreviousPage;
    public GuiButton buttonDone;
    /** The GuiButton to sign this book. */
    public GuiButton buttonSign;
    public GuiButton buttonFinalize;
    public GuiButton buttonCancel;

    public GuiScreenBookUtil(GuiScreenBook parentScreen, boolean bookmark, boolean pauseGame) {
        this.editingPlayer = ObfuscationReflectionHelper.getPrivateValue(GuiScreenBook.class, parentScreen, "field_146468_g", "editingPlayer");
        this.bookObj = ObfuscationReflectionHelper.getPrivateValue(GuiScreenBook.class, parentScreen, "field_146474_h", "bookObj");
        this.bookIsUnsigned = ((Boolean) ObfuscationReflectionHelper.getPrivateValue(GuiScreenBook.class, parentScreen, "field_146475_i", "bookIsUnsigned")).booleanValue();
        this.pauseGame = pauseGame;

        if (this.bookObj.hasTagCompound()) {
            NBTTagCompound nbttagcompound = this.bookObj.getTagCompound();
            this.bookPages = nbttagcompound.getTagList("pages", 8);

            if (this.bookPages != null) {
                this.bookPages = (NBTTagList) this.bookPages.copy();
                this.bookTotalPages = this.bookPages.tagCount();

                if (this.bookTotalPages < 1) {
                    this.bookTotalPages = 1;
                }
            }
            if (bookmark) {
                this.currPage = BookHelper.getCurrentPage(this.bookObj);
                if (this.currPage < 0 || this.currPage >= this.bookTotalPages) {
                    this.currPage = 0;
                }
                this.lastCurrPage = this.currPage;
            }
        }

        if (this.bookPages == null && this.bookIsUnsigned) {
            this.bookPages = new NBTTagList();
            this.bookPages.appendTag(new NBTTagString(""));
            this.bookTotalPages = 1;
        }
    }

    /**
     * Called from the main game loop to update the screen.
     */
    @Override
    public void updateScreen() {
        super.updateScreen();
        this.updateCount++;
    }

    /**
     * Adds the buttons (and other controls) to the screen in question.
     */
    @Override
    public void initGui() {
        this.buttonList.clear();
        Keyboard.enableRepeatEvents(true);

        if (this.bookIsUnsigned) {
            this.buttonList.add(this.buttonSign = new GuiButton(3, this.width / 2 - 100, 4 + this.bookImageHeight, 98, 20, I18n.format("book.signButton", new Object[0])));
            this.buttonList.add(this.buttonDone = new GuiButton(0, this.width / 2 + 2, 4 + this.bookImageHeight, 98, 20, I18n.format("gui.done", new Object[0])));
            this.buttonList.add(this.buttonFinalize = new GuiButton(5, this.width / 2 - 100, 4 + this.bookImageHeight, 98, 20, I18n.format("book.finalizeButton", new Object[0])));
            this.buttonList.add(this.buttonCancel = new GuiButton(4, this.width / 2 + 2, 4 + this.bookImageHeight, 98, 20, I18n.format("gui.cancel", new Object[0])));
        }
        else {
            this.buttonList.add(this.buttonDone = new GuiButton(0, this.width / 2 - 100, 4 + this.bookImageHeight, 200, 20, I18n.format("gui.done", new Object[0])));
        }

        int i = (this.width - this.bookImageWidth) / 2;
        byte b0 = 2;
        this.buttonList.add(this.buttonNextPage = new GuiScreenBookUtil.NextPageButton(1, i + 120, b0 + 154, true));
        this.buttonList.add(this.buttonPreviousPage = new GuiScreenBookUtil.NextPageButton(2, i + 38, b0 + 154, false));
        this.updateButtons();
    }

    /**
     * Called when the screen is unloaded. Used to disable keyboard repeat events
     */
    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private void updateButtons() {
        this.buttonNextPage.visible = !this.settingTitle && (this.currPage < this.bookTotalPages - 1 || this.bookIsUnsigned);
        this.buttonPreviousPage.visible = !this.settingTitle && this.currPage > 0;
        this.buttonDone.visible = !this.bookIsUnsigned || !this.settingTitle;

        if (this.bookIsUnsigned) {
            this.buttonSign.visible = !this.settingTitle;
            this.buttonCancel.visible = this.settingTitle;
            this.buttonFinalize.visible = this.settingTitle;
            this.buttonFinalize.enabled = this.bookTitle.trim().length() > 0;
        }
    }

    private void sendBookToServer(boolean signing) {
        if (this.bookIsUnsigned && this.hasUpdated) {
            if (this.bookPages != null) {
                String s;

                while (this.bookPages.tagCount() > 1) {
                    s = this.bookPages.getStringTagAt(this.bookPages.tagCount() - 1);

                    if (s.length() != 0) {
                        break;
                    }

                    this.bookPages.removeTag(this.bookPages.tagCount() - 1);
                }

                if (this.bookObj.hasTagCompound()) {
                    NBTTagCompound nbttagcompound = this.bookObj.getTagCompound();
                    nbttagcompound.setTag("pages", this.bookPages);
                }
                else {
                    this.bookObj.setTagInfo("pages", this.bookPages);
                }

                s = "MC|BEdit";

                if (signing) {
                    s = "MC|BSign";
                    this.bookObj.setTagInfo("author", new NBTTagString(this.editingPlayer.getCommandSenderName()));
                    this.bookObj.setTagInfo("title", new NBTTagString(this.bookTitle.trim()));
                    this.bookObj.func_150996_a(Items.written_book);
                }

                ByteBuf bytebuf = Unpooled.buffer();

                try {
                    new PacketBuffer(bytebuf).writeItemStackToBuffer(this.bookObj);
                    this.mc.getNetHandler().addToSendQueue(new C17PacketCustomPayload(s, bytebuf));
                }
                catch (Exception exception) {
                    GuiScreenBookUtil.logger.error("Couldn\'t send book info", exception);
                }
                finally {
                    bytebuf.release();
                }
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton p_146284_1_) {
        if (p_146284_1_.enabled) {
            if (p_146284_1_.id == 0) {
                this.mc.displayGuiScreen((GuiScreen) null);
                this.sendBookToServer(false);
            }
            else if (p_146284_1_.id == 3 && this.bookIsUnsigned) {
                this.settingTitle = true;
            }
            else if (p_146284_1_.id == 1) {
                if (this.currPage < this.bookTotalPages - 1) {
                    ++this.currPage;
                }
                else if (this.bookIsUnsigned) {
                    this.addNewPage();

                    if (this.currPage < this.bookTotalPages - 1) {
                        ++this.currPage;
                    }
                }
                this.updateAndSendCurrPage();
            }
            else if (p_146284_1_.id == 2) {
                if (this.currPage > 0) {
                    --this.currPage;
                    this.updateAndSendCurrPage();
                }
            }
            else if (p_146284_1_.id == 5 && this.settingTitle) {
                this.sendBookToServer(true);
                this.mc.displayGuiScreen((GuiScreen) null);
            }
            else if (p_146284_1_.id == 4 && this.settingTitle) {
                this.settingTitle = false;
            }

            this.updateButtons();
        }
    }

    // Updates the book's current page tag and sends it to the server.
    private void updateAndSendCurrPage() {
        if (this.lastCurrPage != this.currPage) {
            this.lastCurrPage = this.currPage;
            BookHelper.setCurrentPage(this.bookObj, this.currPage);
            _LostBooks.CHANNEL.sendToServer(new MessageCurrPage(this.currPage));
        }
    }

    private void addNewPage() {
        if (this.bookPages != null && this.bookPages.tagCount() < 50) {
            this.bookPages.appendTag(new NBTTagString(""));
            ++this.bookTotalPages;
            this.hasUpdated = true;
        }
    }

    /**
     * Fired when a key is typed. This is the equivalent of KeyListener.keyTyped(KeyEvent e).
     */
    @Override
    protected void keyTyped(char p_73869_1_, int p_73869_2_) {
        super.keyTyped(p_73869_1_, p_73869_2_);

        if (this.bookIsUnsigned) {
            if (this.settingTitle) {
                this.func_146460_c(p_73869_1_, p_73869_2_);
            }
            else {
                this.keyTypedInBook(p_73869_1_, p_73869_2_);
            }
        }
    }

    /**
     * Processes keystrokes when editing the text of a book
     */
    private void keyTypedInBook(char p_146463_1_, int p_146463_2_) {
        switch (p_146463_1_) {
            case 22:
                this.func_146459_b(GuiScreen.getClipboardString());
                return;
            default:
                switch (p_146463_2_) {
                    case 14:
                        String s = this.func_146456_p();

                        if (s.length() > 0) {
                            this.func_146457_a(s.substring(0, s.length() - 1));
                        }

                        return;
                    case 28:
                    case 156:
                        this.func_146459_b("\n");
                        return;
                    default:
                        if (ChatAllowedCharacters.isAllowedCharacter(p_146463_1_)) {
                            this.func_146459_b(Character.toString(p_146463_1_));
                        }
                }
        }
    }

    private void func_146460_c(char p_146460_1_, int p_146460_2_) {
        switch (p_146460_2_) {
            case 14:
                if (!this.bookTitle.isEmpty()) {
                    this.bookTitle = this.bookTitle.substring(0, this.bookTitle.length() - 1);
                    this.updateButtons();
                }

                return;
            case 28:
            case 156:
                if (!this.bookTitle.isEmpty()) {
                    this.sendBookToServer(true);
                    this.mc.displayGuiScreen((GuiScreen) null);
                }

                return;
            default:
                if (this.bookTitle.length() < 16 && ChatAllowedCharacters.isAllowedCharacter(p_146460_1_)) {
                    this.bookTitle = this.bookTitle + Character.toString(p_146460_1_);
                    this.updateButtons();
                    this.hasUpdated = true;
                }
        }
    }

    private String func_146456_p() {
        return this.bookPages != null && this.currPage >= 0 && this.currPage < this.bookPages.tagCount() ? this.bookPages.getStringTagAt(this.currPage) : "";
    }

    private void func_146457_a(String p_146457_1_) {
        if (this.bookPages != null && this.currPage >= 0 && this.currPage < this.bookPages.tagCount()) {
            this.bookPages.func_150304_a(this.currPage, new NBTTagString(p_146457_1_));
            this.hasUpdated = true;
        }
    }

    private void func_146459_b(String p_146459_1_) {
        String s1 = this.func_146456_p();
        String s2 = s1 + p_146459_1_;
        int i = this.fontRendererObj.splitStringWidth(s2 + "" + EnumChatFormatting.BLACK + "_", 118);

        if (i <= 118 && s2.length() < 256) {
            this.func_146457_a(s2);
        }
    }

    /**
     * Draws the screen and all the components in it.
     */
    @Override
    public void drawScreen(int p_73863_1_, int p_73863_2_, float p_73863_3_) {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        this.mc.getTextureManager().bindTexture(GuiScreenBookUtil.bookGuiTextures);
        int k = (this.width - this.bookImageWidth) / 2;
        byte b0 = 2;
        this.drawTexturedModalRect(k, b0, 0, 0, this.bookImageWidth, this.bookImageHeight);
        String s;
        String s1;
        int l;

        if (this.settingTitle) {
            s = this.bookTitle;

            if (this.bookIsUnsigned) {
                if (this.updateCount / 6 % 2 == 0) {
                    s = s + "" + EnumChatFormatting.BLACK + "_";
                }
                else {
                    s = s + "" + EnumChatFormatting.GRAY + "_";
                }
            }

            s1 = I18n.format("book.editTitle", new Object[0]);
            l = this.fontRendererObj.getStringWidth(s1);
            this.fontRendererObj.drawString(s1, k + 36 + (116 - l) / 2, b0 + 16 + 16, 0);
            int i1 = this.fontRendererObj.getStringWidth(s);
            this.fontRendererObj.drawString(s, k + 36 + (116 - i1) / 2, b0 + 48, 0);
            String s2 = I18n.format("book.byAuthor", new Object[] { this.editingPlayer.getCommandSenderName() });
            int j1 = this.fontRendererObj.getStringWidth(s2);
            this.fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + s2, k + 36 + (116 - j1) / 2, b0 + 48 + 10, 0);
            String s3 = I18n.format("book.finalizeWarning", new Object[0]);
            this.fontRendererObj.drawSplitString(s3, k + 36, b0 + 80, 116, 0);
        }
        else {
            s = I18n.format("book.pageIndicator", new Object[] { Integer.valueOf(this.currPage + 1), Integer.valueOf(this.bookTotalPages) });
            s1 = "";

            if (this.bookPages != null && this.currPage >= 0 && this.currPage < this.bookPages.tagCount()) {
                s1 = this.bookPages.getStringTagAt(this.currPage);
            }

            if (this.bookIsUnsigned) {
                if (this.fontRendererObj.getBidiFlag()) {
                    s1 = s1 + "_";
                }
                else if (this.updateCount / 6 % 2 == 0) {
                    s1 = s1 + "" + EnumChatFormatting.BLACK + "_";
                }
                else {
                    s1 = s1 + "" + EnumChatFormatting.GRAY + "_";
                }
            }

            l = this.fontRendererObj.getStringWidth(s);
            this.fontRendererObj.drawString(s, k - l + this.bookImageWidth - 44, b0 + 16, 0);
            this.fontRendererObj.drawSplitString(s1, k + 36, b0 + 16 + 16, 116, 0);
        }

        super.drawScreen(p_73863_1_, p_73863_2_, p_73863_3_);
    }

    @SideOnly(Side.CLIENT)
    static class NextPageButton extends GuiButton {
        private final boolean field_146151_o;

        public NextPageButton(int p_i1079_1_, int p_i1079_2_, int p_i1079_3_, boolean p_i1079_4_) {
            super(p_i1079_1_, p_i1079_2_, p_i1079_3_, 23, 13, "");
            this.field_146151_o = p_i1079_4_;
        }

        /**
         * Draws this button to the screen.
         */
        @Override
        public void drawButton(Minecraft p_146112_1_, int p_146112_2_, int p_146112_3_) {
            if (this.visible) {
                boolean flag = p_146112_2_ >= this.xPosition && p_146112_3_ >= this.yPosition && p_146112_2_ < this.xPosition + this.width && p_146112_3_ < this.yPosition + this.height;
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
                p_146112_1_.getTextureManager().bindTexture(GuiScreenBookUtil.bookGuiTextures);
                int k = 0;
                int l = 192;

                if (flag) {
                    k += 23;
                }

                if (!this.field_146151_o) {
                    l += 13;
                }

                this.drawTexturedModalRect(this.xPosition, this.yPosition, k, l, 23, 13);
            }
        }
    }

    /**
     * Returns true if this GUI should pause the game when it is displayed in single-player
     */
    @Override
    public boolean doesGuiPauseGame() {
        return this.pauseGame;
    }

}
